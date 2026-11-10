package vault

import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

/** Datum stored with every locked UTxO.
  *
  * @param owner
  *   the key that funded the vault; may cancel it before the deadline
  * @param beneficiary
  *   the key that may claim the funds from the deadline on
  * @param deadline
  *   POSIX time in milliseconds
  */
case class VaultDatum(
    owner: PubKeyHash,
    beneficiary: PubKeyHash,
    deadline: PosixTime
) derives FromData,
      ToData

/** Redeemer: what the spender wants to do with the vault. */
enum VaultAction derives FromData, ToData:
    /** The beneficiary takes the funds, at or after the deadline. */
    case Claim

    /** The owner takes the funds back, strictly before the deadline. */
    case Cancel

/** Time-locked vault.
  *
  * Funds are locked for a beneficiary until a deadline:
  *   - `Claim`: signed by the beneficiary, and the whole validity interval of the transaction lies
  *     at or after the deadline (`deadline <= validFrom`, lower bound inclusive).
  *   - `Cancel`: signed by the owner, and the whole validity interval lies before the deadline
  *     (`validTo <= deadline`, upper bound exclusive).
  *
  * The two time windows do not overlap, so once the deadline passes the owner can no longer take
  * the funds back. The validator checks no outputs: the authorised signer builds the transaction
  * and decides where the funds go, so there is nothing to double-satisfy.
  */
@Compile
object VaultValidator extends Validator {

    inline override def spend(
        datum: Option[Data],
        redeemer: Data,
        tx: TxInfo,
        ownRef: TxOutRef
    ): Unit = {
        val vault = datum.getOrFail(MissingDatum).to[VaultDatum]
        redeemer.to[VaultAction] match
            case VaultAction.Claim =>
                require(tx.isSignedBy(vault.beneficiary), NotSignedByBeneficiary)
                val validFrom = tx.validFromOrFail(NoLowerBound)
                require(vault.deadline <= validFrom, DeadlineNotReached)
            case VaultAction.Cancel =>
                require(tx.isSignedBy(vault.owner), NotSignedByOwner)
                val validTo = tx.validToOrFail(NoUpperBound)
                require(validTo <= vault.deadline, DeadlinePassed)
    }

    // Error messages (kept in the script only when compiled with error traces)
    inline val MissingDatum = "Vault datum missing"
    inline val NotSignedByBeneficiary = "Not signed by beneficiary"
    inline val NotSignedByOwner = "Not signed by owner"
    inline val NoLowerBound = "Validity range must have a lower bound"
    inline val NoUpperBound = "Validity range must have an upper bound"
    inline val DeadlineNotReached = "Deadline not reached"
    inline val DeadlinePassed = "Deadline passed"
}
