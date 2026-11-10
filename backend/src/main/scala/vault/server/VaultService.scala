package vault.server

import scalus.cardano.address.{Address, Network}
import scalus.cardano.ledger.*
import scalus.cardano.node.{BlockchainProvider, BlockfrostProvider, Emulator}
import scalus.serialization.cbor.Cbor
import scalus.testing.kit.Party
import scalus.testing.kit.TestUtil
import scalus.utils.Hex.bytesToHex
import scalus.utils.await
import vault.offchain.{Cip30, VaultTransactions, VaultUtxo}

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** Raised for problems the user can fix (bad input, wrong wallet, too early,
  * ...).
  */
class UserError(message: String) extends RuntimeException(message)

/** Everything the REST API does: chain queries through a BlockchainProvider
  * (Blockfrost in production, Emulator in dev/testing) and transaction building
  * with the Scalus TxBuilder. Transactions are returned unsigned; the browser
  * wallet (or simulated dev wallet) signs them.
  */
class VaultService(
    val provider: BlockchainProvider,
    val networkName: String,
    val emulatorOpt: Option[Emulator] = None
) {

  private val timeout = 60.seconds
  private val faucetCounter = new AtomicInteger(100)

  /** Protocol parameters are cached by the provider; refreshed in
    * [[refreshParams]].
    */
  def env: CardanoInfo = provider.cardanoInfo
  private def txs: VaultTransactions = VaultTransactions(env)

  def isEmulator: Boolean = emulatorOpt.isDefined

  def currentTime: Instant = emulatorOpt match {
    case Some(em) =>
      Instant.ofEpochMilli(env.slotConfig.slotToTime(em.currentSlotSync))
    case None => Instant.now()
  }

  def currentSlot: Long = emulatorOpt match {
    case Some(em) => em.currentSlotSync
    case None     => env.slotConfig.timeToSlot(System.currentTimeMillis())
  }

  def refreshParams(): Unit = provider match {
    case bf: BlockfrostProvider => bf.refreshCardanoInfo.await(timeout)
    case _                      => ()
  }

  private def utxosAt(address: Address): Utxos =
    provider.findUtxos(address).await(timeout) match
      case Right(utxos) => utxos
      case Left(error)  => throw RuntimeException(s"UTxO query failed: $error")

  private def findVault(txHash: String, index: Int): VaultUtxo =
    txs
      .vaults(utxosAt(txs.scriptAddress))
      .find(v =>
        v.utxo.input.transactionId.toHex == txHash && v.utxo.input.index == index
      )
      .getOrElse(
        throw UserError(s"Vault $txHash#$index not found (already spent?)")
      )

  private def requireNetwork(address: Address): Address = {
    if !isEmulator && address.getNetwork.exists(_ != env.network) then
      throw UserError(
        s"Address is not on $networkName; switch your wallet network"
      )
    address
  }

  private def parseWalletAddress(address: String): Address =
    requireNetwork(
      try Cip30.parseAddress(address)
      catch case e: IllegalArgumentException => throw UserError(e.getMessage)
    )

  private def unsignedTxJson(tx: Transaction): ujson.Value =
    ujson.Obj(
      "tx" -> Cip30.txToHex(tx),
      "txHash" -> tx.id.toHex,
      "fee" -> tx.body.value.fee.value.toDouble
    )

  // ---------------------------------------------------------------- queries

  def config: ujson.Value = {
    val devWallets = emulatorOpt match {
      case Some(_) =>
        ujson.Arr.from(
          Seq(Party.Alice, Party.Bob, Party.Eve, Party.Dave).map { p =>
            val addr = p.address(env.network)
            ujson.Obj(
              "name" -> p.toString,
              "address" -> Cip30.toBech32(addr),
              "pkh" -> p.addrKeyHash.toHex
            )
          }
        )
      case None => ujson.Arr()
    }

    ujson.Obj(
      "network" -> networkName,
      "networkId" -> env.network.networkId.toInt,
      "isEmulator" -> isEmulator,
      "currentSlot" -> currentSlot.toDouble,
      "currentTime" -> currentTime.toEpochMilli.toDouble,
      "scriptAddress" -> Cip30.toBech32(txs.scriptAddress),
      "scriptHash" -> txs.contract.script.scriptHash.toHex,
      "scriptSize" -> txs.contract.script.script.size,
      "devWallets" -> devWallets,
      "explorer" -> (networkName match {
        case "emulator" => ""
        case "mainnet"  => "https://cardanoscan.io"
        case other      => s"https://$other.cardanoscan.io"
      })
    )
  }

  def addressInfo(address: String): ujson.Value = {
    val addr = parseWalletAddress(address)
    val pkh =
      try VaultTransactions.paymentKeyHash(addr).toHex
      catch case e: IllegalArgumentException => throw UserError(e.getMessage)
    val lovelace = utxosAt(addr).values.map(_.value.coin.value).sum
    ujson.Obj(
      "address" -> Cip30.toBech32(addr),
      "pkh" -> pkh,
      "lovelace" -> lovelace.toDouble
    )
  }

  def vaults: ujson.Value =
    ujson.Arr.from(txs.vaults(utxosAt(txs.scriptAddress)).map { v =>
      ujson.Obj(
        "txHash" -> v.utxo.input.transactionId.toHex,
        "index" -> v.utxo.input.index,
        "lovelace" -> v.lovelace.toDouble,
        "owner" -> v.ownerPkh,
        "beneficiary" -> v.beneficiaryPkh,
        "deadline" -> v.deadline.toDouble
      )
    })

  // ---------------------------------------------------------------- transactions

  def buildLock(
      address: String,
      beneficiary: String,
      lovelace: Long,
      deadline: Long
  ): ujson.Value = {
    val sponsor = parseWalletAddress(address)
    val beneficiaryAddr = parseWalletAddress(beneficiary)
    val beneficiaryPkh =
      try VaultTransactions.paymentKeyHash(beneficiaryAddr)
      catch case e: IllegalArgumentException => throw UserError(e.getMessage)
    if lovelace < 2_000_000L then throw UserError("Lock at least 2 ADA")
    if deadline <= currentTime.toEpochMilli then
      throw UserError("The deadline must be in the future")
    val tx = txs.lock(
      utxos = utxosAt(sponsor),
      sponsor = sponsor,
      beneficiary = beneficiaryPkh,
      lovelace = lovelace,
      deadline = Instant.ofEpochMilli(deadline)
    )
    unsignedTxJson(tx)
  }

  def buildClaim(address: String, txHash: String, index: Int): ujson.Value = {
    val beneficiary = parseWalletAddress(address)
    val vault = findVault(txHash, index)
    val now = currentTime
    val deadline =
      VaultTransactions.roundUpToSecond(Instant.ofEpochMilli(vault.deadline))
    if deadline.isAfter(now) then
      throw UserError("The deadline has not been reached yet")
    // Lower bound: slightly in the past (tolerates slot drift), never before the deadline
    val slightlyAgo = Instant.ofEpochSecond(now.getEpochSecond - 120)
    val validFrom =
      if slightlyAgo.isAfter(deadline) then slightlyAgo else deadline
    val tx =
      try txs.claim(utxosAt(beneficiary), vault, beneficiary, validFrom)
      catch case e: IllegalArgumentException => throw UserError(e.getMessage)
    unsignedTxJson(tx)
  }

  def buildCancel(address: String, txHash: String, index: Int): ujson.Value = {
    val owner = parseWalletAddress(address)
    val vault = findVault(txHash, index)
    val now = currentTime
    val deadline = Instant.ofEpochSecond(Math.floorDiv(vault.deadline, 1000L))
    // Upper bound (exclusive): at most 15 minutes ahead, never after the deadline
    val inFifteen = Instant.ofEpochSecond(now.getEpochSecond + 15 * 60)
    val validTo = if inFifteen.isBefore(deadline) then inFifteen else deadline
    if validTo.getEpochSecond - now.getEpochSecond < 10 then
      throw UserError("Too close to (or past) the deadline to cancel")
    val tx =
      try txs.cancel(utxosAt(owner), vault, owner, validTo)
      catch case e: IllegalArgumentException => throw UserError(e.getMessage)
    unsignedTxJson(tx)
  }

  /** Merge the CIP-30 witness set into the unsigned transaction and submit it.
    */
  def submit(txHex: String, witnessHex: String): ujson.Value = {
    val unsigned =
      try Cip30.txFromHex(txHex)
      catch
        case e: Exception =>
          throw UserError(s"Invalid transaction CBOR: ${e.getMessage}")
    val witnesses =
      try Cip30.witnessSetFromHex(witnessHex)
      catch
        case e: Exception =>
          throw UserError(s"Invalid witness CBOR: ${e.getMessage}")
    val signed = Cip30.addWitnesses(unsigned, witnesses)
    provider.submit(signed).await(timeout) match
      case Right(hash) => ujson.Obj("txHash" -> hash.toHex)
      case Left(error) => throw UserError(s"Submission rejected: $error")
  }

  // ---------------------------------------------------------------- emulator specific

  def advanceTime(seconds: Long): ujson.Value = {
    val em = emulatorOpt.getOrElse(
      throw UserError("Time travel is only available in Emulator mode")
    )
    em.tick(seconds)
    ujson.Obj(
      "currentSlot" -> em.currentSlotSync.toDouble,
      "currentTime" -> currentTime.toEpochMilli.toDouble,
      "advancedSeconds" -> seconds.toDouble
    )
  }

  def fundAddress(addressStr: String, ada: Long = 1000): ujson.Value = {
    val em = emulatorOpt.getOrElse(
      throw UserError("Faucet is only available in Emulator mode")
    )
    val addr = parseWalletAddress(addressStr)
    val idx = faucetCounter.incrementAndGet()
    val input = TransactionInput(TestUtil.genesisHash, idx)
    val output = TransactionOutput(addr, Value.ada(ada))
    em.addUtxo(input, output)
    ujson.Obj(
      "address" -> Cip30.toBech32(addr),
      "fundedAda" -> ada.toDouble
    )
  }

  def devSign(txHex: String, partyName: String): ujson.Value = {
    val em = emulatorOpt.getOrElse(
      throw UserError("Dev signing is only available in Emulator mode")
    )
    val party =
      try Party.valueOf(partyName)
      catch
        case _: Exception => throw UserError(s"Unknown dev party: $partyName")
    val unsigned = Cip30.txFromHex(txHex)
    val signed = party.signer.sign(unsigned)
    val witnessBytes = Cbor.encode(signed.witnessSet)
    ujson.Obj(
      "witness" -> bytesToHex(witnessBytes),
      "party" -> partyName
    )
  }
}

object VaultService {

  /** Configure from the environment. If `NETWORK=emulator` or if
    * `BLOCKFROST_PROJECT_ID` is absent, launches an in-memory Cardano ledger
    * Emulator with funded test parties.
    */
  def fromEnv()(using ExecutionContext): VaultService = {
    val rawNetwork = sys.env.getOrElse("NETWORK", "").trim.toLowerCase
    println(s"Network: $rawNetwork")
    val apiKeyOpt =
      sys.env.get("BLOCKFROST_PROJECT_ID").map(_.trim).filter(_.nonEmpty)

    if (rawNetwork == "emulator" || apiKeyOpt.isEmpty) {
      println(
        "=== Running in Scalus Emulator Mode (In-Memory Cardano Ledger) ==="
      )
      println(
        "Pre-funding dev parties: Alice, Bob, Eve, Dave with 10,000 ADA each."
      )
      val defaultParties = Seq(Party.Alice, Party.Bob, Party.Eve, Party.Dave)
      val addresses = defaultParties.flatMap(p =>
        Seq(p.address(Network.Mainnet), p.address(Network.Testnet))
      )
      val emulator = Emulator.withAddresses(addresses)
      val initialSlot =
        emulator.cardanoInfo.slotConfig.timeToSlot(System.currentTimeMillis())
      emulator.setSlot(initialSlot)
      VaultService(emulator, "emulator", Some(emulator))
    } else {
      val apiKey = apiKeyOpt.get
      val network = if (rawNetwork.isEmpty) "preprod" else rawNetwork
      val provider: Future[BlockfrostProvider] = network match
        case "preprod" => BlockfrostProvider.preprod(apiKey)
        case "preview" => BlockfrostProvider.preview(apiKey)
        case "mainnet" => BlockfrostProvider.mainnet(apiKey)
        case other => throw IllegalStateException(s"Unknown NETWORK: $other")
      VaultService(provider.await(60.seconds), network, None)
    }
  }
}
