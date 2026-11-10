package vault.offchain

import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.serialization.cbor.Cbor
import scalus.utils.Hex.{bytesToHex, hexToBytes}

import scala.util.Try

/** Helpers for talking to CIP-30 browser wallets, which exchange addresses, transactions and
  * witness sets as hex-encoded CBOR.
  */
object Cip30 {

    /** Parse an address given either as CIP-30 hex bytes or as bech32. */
    def parseAddress(s: String): Address = {
        val str = s.trim
        Try(Address.fromBytes(hexToBytes(str))).orElse(Try(Address.fromBech32(str))).getOrElse {
            throw IllegalArgumentException(s"Invalid address: $str")
        }
    }

    def toBech32(address: Address): String =
        address.encode.getOrElse(address.toBytes.toHex)

    def txToHex(tx: Transaction): String = bytesToHex(tx.toCbor)

    def txFromHex(hex: String): Transaction = Transaction.fromCbor(hexToBytes(hex.trim))

    /** Decode the witness set returned by `api.signTx(tx, partialSign)`. */
    def witnessSetFromHex(hex: String): TransactionWitnessSet = {
        val bytes = hexToBytes(hex.trim)
        given OriginalCborByteArray = OriginalCborByteArray(bytes)
        given ProtocolVersion = ProtocolVersion.conwayPV
        Cbor.decode[TransactionWitnessSet](bytes)
    }

    /** Add the wallet's vkey witnesses to the unsigned transaction.
      *
      * Only the witness set changes; the body keeps its original bytes, so the transaction id the
      * wallet signed stays the same.
      */
    def addWitnesses(tx: Transaction, walletWitnesses: TransactionWitnessSet): Transaction =
        tx.withWitness { ws =>
            ws.copy(vkeyWitnesses =
                TaggedSortedSet(ws.vkeyWitnesses.toSet ++ walletWitnesses.vkeyWitnesses.toSet)
            )
        }
}
