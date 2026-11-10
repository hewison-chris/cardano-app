package vault.offchain

import scalus.cardano.address.{Address, ShelleyAddress, ShelleyPaymentPart}
import scalus.cardano.ledger.*
import scalus.cardano.onchain.plutus.v3.PubKeyHash
import scalus.cardano.txbuilder.*
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.Data
import vault.{VaultAction, VaultContract, VaultDatum}

import java.time.Instant
import scala.util.Try

/** A vault UTxO as seen off-chain, with its decoded datum. */
case class VaultUtxo(utxo: Utxo, datum: VaultDatum) {
    def lovelace: Long = utxo.output.value.coin.value
    def deadline: Long = datum.deadline.toLong
    def ownerPkh: String = datum.owner.hash.toHex
    def beneficiaryPkh: String = datum.beneficiary.hash.toHex
}

/** Builds the *unsigned* transactions of the vault dApp.
  *
  * Every method returns a balanced transaction (fees, change and, for script spends, collateral
  * and execution units computed) that the user's wallet only has to sign.
  *
  * @param env
  *   protocol parameters, network and slot configuration
  * @param contract
  *   the compiled validator
  * @param evaluator
  *   script evaluator used while balancing; by default the scripts are run and their execution
  *   units computed, so a transaction the validator would reject fails here, before signing
  */
case class VaultTransactions(
    env: CardanoInfo,
    contract: PlutusV3[Data => Unit] = VaultContract.compiled,
    evaluator: Option[PlutusScriptEvaluator] = None
) {
    val scriptAddress: Address = contract.address(env.network)

    private def builder: TxBuilder = evaluator.fold(TxBuilder(env))(TxBuilder(env, _))

    /** Lock `lovelace` in the vault for `beneficiary` until `deadline`. */
    def lock(
        utxos: Utxos,
        sponsor: Address,
        beneficiary: AddrKeyHash,
        lovelace: Long,
        deadline: Instant
    ): Transaction = {
        require(lovelace >= 2_000_000L, "Lock at least 2 ADA")
        val owner = VaultTransactions.paymentKeyHash(sponsor)
        val datum = VaultDatum(
          owner = PubKeyHash(owner),
          beneficiary = PubKeyHash(beneficiary),
          deadline = BigInt(VaultTransactions.roundUpToSecond(deadline).toEpochMilli)
        )
        builder
            .payTo(scriptAddress, Value.lovelace(lovelace), datum)
            .complete(availableUtxos = utxos, sponsor = sponsor)
            .transaction
    }

    /** The beneficiary claims a vault. `validFrom` must be at or after the deadline and not in the
      * future of the chain tip.
      */
    def claim(utxos: Utxos, vault: VaultUtxo, beneficiary: Address, validFrom: Instant): Transaction = {
        val pkh = VaultTransactions.paymentKeyHash(beneficiary)
        require(pkh.toHex == vault.beneficiaryPkh, "Connected wallet is not the beneficiary")
        require(validFrom.toEpochMilli >= vault.deadline, "Deadline not reached yet")
        builder
            .spend(vault.utxo, VaultAction.Claim, contract)
            .requireSignature(pkh)
            .payTo(beneficiary, vault.utxo.output.value)
            .validFrom(validFrom)
            .complete(availableUtxos = utxos, sponsor = beneficiary)
            .transaction
    }

    /** The owner cancels a vault. `validTo` (exclusive) must not be after the deadline. */
    def cancel(utxos: Utxos, vault: VaultUtxo, owner: Address, validTo: Instant): Transaction = {
        val pkh = VaultTransactions.paymentKeyHash(owner)
        require(pkh.toHex == vault.ownerPkh, "Connected wallet is not the owner")
        require(validTo.toEpochMilli <= vault.deadline, "Deadline already passed")
        builder
            .spend(vault.utxo, VaultAction.Cancel, contract)
            .requireSignature(pkh)
            .payTo(owner, vault.utxo.output.value)
            .validTo(validTo)
            .complete(availableUtxos = utxos, sponsor = owner)
            .transaction
    }

    /** Decode the vault UTxOs among `utxos`; UTxOs without a valid inline datum are skipped. */
    def vaults(utxos: Utxos): Seq[VaultUtxo] =
        utxos.toSeq
            .filter { case (_, out) => out.address == scriptAddress }
            .flatMap { case (in, out) =>
                out.inlineDatum
                    .flatMap(d => Try(d.to[VaultDatum]).toOption)
                    .map(datum => VaultUtxo(Utxo(in, out), datum))
            }
            .sortBy(v => (v.deadline, v.utxo.input.transactionId.toHex, v.utxo.input.index))
}

object VaultTransactions {

    /** The payment key hash of a Shelley key address. */
    def paymentKeyHash(address: Address): AddrKeyHash = address match
        case ShelleyAddress(_, ShelleyPaymentPart.Key(hash), _) => hash
        case _ => throw IllegalArgumentException("Expected a Shelley address with a payment key")

    /** Slots are whole seconds; a deadline on a second boundary converts to a slot exactly. */
    def roundUpToSecond(t: Instant): Instant =
        if t.getNano == 0 then t else Instant.ofEpochSecond(t.getEpochSecond + 1)
}
