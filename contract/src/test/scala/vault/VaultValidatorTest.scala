package vault

import org.scalatest.funsuite.AnyFunSuite
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.node.Emulator
import scalus.cardano.txbuilder.{TransactionSigner, TxBuilder, TxBuilderException}
import scalus.serialization.cbor.Cbor
import scalus.testing.kit.Party.{Alice, Bob, Eve}
import scalus.testing.kit.TestUtil
import scalus.utils.Hex.bytesToHex
import scalus.utils.await
import vault.offchain.{Cip30, VaultTransactions, VaultUtxo}

import java.time.Instant

class VaultValidatorTest extends AnyFunSuite {

    private given env: CardanoInfo = TestUtil.testEnvironment

    // Error traces make script failures readable in the test output
    private val contract = VaultContract.compiled.withErrorTraces
    private val txs = VaultTransactions(env, contract)
    // Skips script evaluation while balancing, so a transaction the validator rejects can still be
    // built and submitted, and the Emulator (which always runs the scripts) rejects it.
    private val unchecked = PlutusScriptEvaluator.constMaxBudget(env)

    private val lockAmount = 25_000_000L
    private val deadlineSlot: SlotNo = 1_000
    private val deadline: Instant = env.slotConfig.slotToInstant(deadlineSlot)

    private def newEmulator(): Emulator =
        Emulator.withAddresses(Seq(Alice.address, Bob.address, Eve.address))

    private def utxosOf(emulator: Emulator, address: Address): Utxos =
        emulator.findUtxos(address).await().toOption.get

    /** Signs like a CIP-30 wallet: the wallet returns only a witness set (hex CBOR), which the
      * backend merges into the unsigned transaction it built (also passed around as hex CBOR).
      */
    private def walletSign(unsigned: Transaction, signer: TransactionSigner): Transaction = {
        val unsignedHex = Cip30.txToHex(unsigned)
        val walletWitnessHex = bytesToHex(Cbor.encode(signer.sign(unsigned).witnessSet))
        Cip30.addWitnesses(Cip30.txFromHex(unsignedHex), Cip30.witnessSetFromHex(walletWitnessHex))
    }

    private def submitOk(emulator: Emulator, tx: Transaction): Unit =
        emulator.submit(tx).await() match
            case Right(_)    => ()
            case Left(error) => fail(s"Transaction rejected: $error")

    private def submitFails(emulator: Emulator, tx: Transaction): Unit =
        assert(emulator.submit(tx).await().isLeft, "Transaction should have been rejected")

    /** Alice locks funds for Bob. */
    private def lock(emulator: Emulator): VaultUtxo = {
        val unsigned = txs.lock(
          utxos = utxosOf(emulator, Alice.address),
          sponsor = Alice.address,
          beneficiary = Bob.addrKeyHash,
          lovelace = lockAmount,
          deadline = deadline
        )
        submitOk(emulator, walletSign(unsigned, Alice.signer))
        val vaults = txs.vaults(utxosOf(emulator, txs.scriptAddress))
        assert(vaults.size == 1)
        vaults.head
    }

    test("lock creates a vault UTxO with the right datum") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        assert(vault.lovelace == lockAmount)
        assert(vault.ownerPkh == Alice.addrKeyHash.toHex)
        assert(vault.beneficiaryPkh == Bob.addrKeyHash.toHex)
        assert(vault.deadline == deadline.toEpochMilli)
    }

    test("wallet witness merge keeps the transaction id") {
        val emulator = newEmulator()
        val unsigned = txs.lock(
          utxosOf(emulator, Alice.address),
          Alice.address,
          Bob.addrKeyHash,
          lockAmount,
          deadline
        )
        val signed = walletSign(unsigned, Alice.signer)
        assert(signed.id == unsigned.id)
        assert(signed.witnessSet.vkeyWitnesses.toSet.size == 1)
    }

    test("beneficiary claims after the deadline") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot + 10)
        val validFrom = env.slotConfig.slotToInstant(deadlineSlot + 10)
        val claim = txs.claim(utxosOf(emulator, Bob.address), vault, Bob.address, validFrom)
        submitOk(emulator, walletSign(claim, Bob.signer))
        assert(txs.vaults(utxosOf(emulator, txs.scriptAddress)).isEmpty)
    }

    test("beneficiary claims exactly at the deadline") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot)
        val claim = txs.claim(utxosOf(emulator, Bob.address), vault, Bob.address, deadline)
        submitOk(emulator, walletSign(claim, Bob.signer))
    }

    test("owner cancels before the deadline") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot - 100)
        val cancel = txs.cancel(utxosOf(emulator, Alice.address), vault, Alice.address, deadline)
        submitOk(emulator, walletSign(cancel, Alice.signer))
        assert(txs.vaults(utxosOf(emulator, txs.scriptAddress)).isEmpty)
    }

    test("the default evaluator refuses to build a claim the validator rejects") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        // Bypass the off-chain guard and ask the builder directly for an early claim
        val early = env.slotConfig.slotToInstant(deadlineSlot - 1)
        assertThrows[TxBuilderException] {
            TxBuilder(env)
                .spend(vault.utxo, VaultAction.Claim, contract)
                .requireSignature(Bob.addrKeyHash)
                .payTo(Bob.address, vault.utxo.output.value)
                .validFrom(early)
                .complete(utxosOf(emulator, Bob.address), Bob.address)
        }
    }

    test("fail: beneficiary claims before the deadline") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot - 1)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Claim, contract)
            .requireSignature(Bob.addrKeyHash)
            .payTo(Bob.address, vault.utxo.output.value)
            .validFrom(env.slotConfig.slotToInstant(deadlineSlot - 1))
            .complete(utxosOf(emulator, Bob.address), Bob.address)
            .sign(Bob.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("fail: claim without a validity lower bound") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot + 10)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Claim, contract)
            .requireSignature(Bob.addrKeyHash)
            .payTo(Bob.address, vault.utxo.output.value)
            .complete(utxosOf(emulator, Bob.address), Bob.address)
            .sign(Bob.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("fail: a stranger claims after the deadline") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot + 10)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Claim, contract)
            .requireSignature(Eve.addrKeyHash)
            .payTo(Eve.address, vault.utxo.output.value)
            .validFrom(env.slotConfig.slotToInstant(deadlineSlot + 10))
            .complete(utxosOf(emulator, Eve.address), Eve.address)
            .sign(Eve.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("fail: the owner claims with the Claim redeemer") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot + 10)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Claim, contract)
            .requireSignature(Alice.addrKeyHash)
            .payTo(Alice.address, vault.utxo.output.value)
            .validFrom(env.slotConfig.slotToInstant(deadlineSlot + 10))
            .complete(utxosOf(emulator, Alice.address), Alice.address)
            .sign(Alice.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("fail: owner cancels with a validity range past the deadline") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot - 100)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Cancel, contract)
            .requireSignature(Alice.addrKeyHash)
            .payTo(Alice.address, vault.utxo.output.value)
            .validTo(env.slotConfig.slotToInstant(deadlineSlot + 1))
            .complete(utxosOf(emulator, Alice.address), Alice.address)
            .sign(Alice.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("fail: cancel without a validity upper bound") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot - 100)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Cancel, contract)
            .requireSignature(Alice.addrKeyHash)
            .payTo(Alice.address, vault.utxo.output.value)
            .complete(utxosOf(emulator, Alice.address), Alice.address)
            .sign(Alice.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("fail: the beneficiary cancels") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        emulator.setSlot(deadlineSlot - 100)
        val tx = TxBuilder(env, unchecked)
            .spend(vault.utxo, VaultAction.Cancel, contract)
            .requireSignature(Bob.addrKeyHash)
            .payTo(Bob.address, vault.utxo.output.value)
            .validTo(deadline)
            .complete(utxosOf(emulator, Bob.address), Bob.address)
            .sign(Bob.signer)
            .transaction
        submitFails(emulator, tx)
    }

    test("off-chain guards reject the wrong wallet") {
        val emulator = newEmulator()
        val vault = lock(emulator)
        assertThrows[IllegalArgumentException] {
            txs.claim(utxosOf(emulator, Eve.address), vault, Eve.address, deadline)
        }
        assertThrows[IllegalArgumentException] {
            txs.cancel(utxosOf(emulator, Bob.address), vault, Bob.address, deadline)
        }
    }

    test("CIP-30 address parsing accepts hex and bech32") {
        val bech32 = Cip30.toBech32(Alice.address)
        assert(Cip30.parseAddress(bech32) == Alice.address)
        assert(Cip30.parseAddress(Alice.address.toBytes.toHex) == Alice.address)
    }
}
