package vault.server

import org.scalatest.funsuite.AnyFunSuite
import scalus.cardano.ledger.*
import scalus.cardano.node.Emulator
import scalus.serialization.cbor.Cbor
import scalus.testing.kit.Party.{Alice, Bob}
import scalus.testing.kit.TestUtil
import scalus.utils.Hex.bytesToHex
import vault.offchain.Cip30

class VaultServiceTest extends AnyFunSuite {

    private given env: CardanoInfo = TestUtil.testEnvironment

    test("VaultService queries config and builds lock tx on Emulator") {
        val emulator = Emulator.withAddresses(Seq(Alice.address, Bob.address))
        val service = VaultService(emulator, "mainnet")

        val config = service.config
        assert(config("network").str == "mainnet")
        assert(config("scriptAddress").str.nonEmpty)

        val aliceInfo = service.addressInfo(Alice.address.toBytes.toHex)
        assert(aliceInfo("pkh").str == Alice.addrKeyHash.toHex)
        assert(aliceInfo("lovelace").num > 0)

        val deadline = System.currentTimeMillis() + 3600 * 1000
        val lockTxJson = service.buildLock(
          address = Alice.address.toBytes.toHex,
          beneficiary = Bob.address.toBytes.toHex,
          lovelace = 10_000_000L,
          deadline = deadline
        )

        val txHex = lockTxJson("tx").str
        assert(txHex.nonEmpty)
        assert(lockTxJson("fee").num > 0)

        // Sign with Alice's signer
        val unsignedTx = Cip30.txFromHex(txHex)
        val signedTx = Alice.signer.sign(unsignedTx)
        val witnessHex = bytesToHex(Cbor.encode(signedTx.witnessSet))

        // Submit through service
        val submitRes = service.submit(txHex, witnessHex)
        assert(submitRes("txHash").str == unsignedTx.id.toHex)

        // Vault list should now show the new vault
        val vaults = service.vaults.arr
        assert(vaults.length == 1)
        assert(vaults.head("lovelace").num == 10_000_000.0)
        assert(vaults.head("owner").str == Alice.addrKeyHash.toHex)
        assert(vaults.head("beneficiary").str == Bob.addrKeyHash.toHex)
    }

    test("VaultService rejects invalid user inputs with UserError") {
        val emulator = Emulator.withAddresses(Seq(Alice.address, Bob.address))
        val service = VaultService(emulator, "mainnet")

        // Deadline in the past
        assertThrows[UserError] {
            service.buildLock(
              address = Alice.address.toBytes.toHex,
              beneficiary = Bob.address.toBytes.toHex,
              lovelace = 10_000_000L,
              deadline = System.currentTimeMillis() - 1000
            )
        }

        // Less than 2 ADA
        assertThrows[UserError] {
            service.buildLock(
              address = Alice.address.toBytes.toHex,
              beneficiary = Bob.address.toBytes.toHex,
              lovelace = 1_000_000L,
              deadline = System.currentTimeMillis() + 60000
            )
        }
    }
}
