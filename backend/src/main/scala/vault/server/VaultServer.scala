package vault.server

import scalus.cardano.txbuilder.TxBuilderException

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.ExecutionContext

/** REST API + static file server for the Elm frontend.
  *
  * {{{
  * GET  /                      Elm app (frontend/public/index.html)
  * GET  /static/...            frontend/public assets (elm.js, wallet.js, style.css)
  * GET  /api/config            network, script address and hash
  * POST /api/address           {address}                                -> {address, pkh, lovelace}
  * GET  /api/vaults            all vault UTxOs with decoded datums
  * POST /api/tx/lock           {address, beneficiary, lovelace, deadline} -> {tx, txHash, fee}
  * POST /api/tx/claim          {address, txHash, index}                 -> {tx, txHash, fee}
  * POST /api/tx/cancel         {address, txHash, index}                 -> {tx, txHash, fee}
  * POST /api/tx/submit         {tx, witness}                            -> {txHash}
  * }}}
  * `address` is the wallet's CIP-30 change address (hex) or a bech32 address; `tx` and `witness`
  * are hex CBOR.
  */
object VaultServer extends cask.MainRoutes {

    override def host: String = sys.env.getOrElse("HOST", "localhost")
    override def port: Int = sys.env.get("PORT").map(_.toInt).getOrElse(8080)

    private val publicDir: Path = Paths.get(sys.env.getOrElse("PUBLIC_DIR", "frontend/public"))

    lazy val service: VaultService = VaultService.fromEnv()(using ExecutionContext.global)

    override def main(args: Array[String]): Unit = {
        val s = service // fail fast on a bad configuration
        println(s"Vault script address (${s.networkName}): ${s.config("scriptAddress").str}")
        println(s"Serving on http://$host:$port")
        super.main(args)
    }

    // ------------------------------------------------------------------ helpers

    private val corsHeaders = Seq(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type"
    )

    private def json(value: ujson.Value, status: Int = 200): cask.Response[ujson.Value] =
        cask.Response(
          value,
          statusCode = status,
          headers = Seq("Content-Type" -> "application/json") ++ corsHeaders
        )

    @cask.route("/api/:subpath*", methods = Seq("options"))
    def optionsRoute(subpath: Seq[String]): cask.Response[String] =
        cask.Response("", statusCode = 200, headers = corsHeaders)

    /** Turn exceptions into `{"error": "..."}` responses the UI can show. */
    private def handle(body: => ujson.Value): cask.Response[ujson.Value] =
        try json(body)
        catch
            case e: UserError => json(ujson.Obj("error" -> e.getMessage), 400)
            case e: TxBuilderException =>
                json(ujson.Obj("error" -> s"Could not build the transaction: ${e.getMessage}"), 400)
            case e: Throwable =>
                e.printStackTrace()
                json(ujson.Obj("error" -> s"Server error: ${e.getMessage}"), 500)

    // ------------------------------------------------------------------ static

    @cask.get("/")
    def index(): cask.Response[String] = {
        val file = publicDir.resolve("index.html")
        if Files.exists(file) then
            cask.Response(Files.readString(file), headers = Seq("Content-Type" -> "text/html"))
        else cask.Response("frontend/public/index.html not found", statusCode = 404)
    }

    @cask.staticFiles("/static")
    def staticAssets(): String = publicDir.toString

    // ------------------------------------------------------------------ api

    @cask.get("/api/config")
    def config(): cask.Response[ujson.Value] = handle(service.config)

    @cask.postJson("/api/address")
    def address(address: String): cask.Response[ujson.Value] =
        handle(service.addressInfo(address))

    @cask.get("/api/vaults")
    def vaults(): cask.Response[ujson.Value] = handle(service.vaults)

    @cask.postJson("/api/tx/lock")
    def lock(address: String, beneficiary: String, lovelace: Long, deadline: Long)
        : cask.Response[ujson.Value] =
        handle(service.buildLock(address, beneficiary, lovelace, deadline))

    @cask.postJson("/api/tx/claim")
    def claim(address: String, txHash: String, index: Int): cask.Response[ujson.Value] =
        handle(service.buildClaim(address, txHash, index))

    @cask.postJson("/api/tx/cancel")
    def cancel(address: String, txHash: String, index: Int): cask.Response[ujson.Value] =
        handle(service.buildCancel(address, txHash, index))

    @cask.postJson("/api/tx/submit")
    def submit(tx: String, witness: String): cask.Response[ujson.Value] =
        handle(service.submit(tx, witness))

    // ------------------------------------------------------------------ emulator routes

    @cask.postJson("/api/emulator/advance-time")
    def advanceTime(seconds: Long = 3600L): cask.Response[ujson.Value] =
        handle(service.advanceTime(seconds))

    @cask.postJson("/api/emulator/fund")
    def fund(address: String, ada: Long = 1000L): cask.Response[ujson.Value] =
        handle(service.fundAddress(address, ada))

    @cask.postJson("/api/emulator/dev-sign")
    def devSign(tx: String, party: String): cask.Response[ujson.Value] =
        handle(service.devSign(tx, party))

    initialize()
}
