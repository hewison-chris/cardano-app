package vault

import scalus.compiler.Options
import scalus.uplc.PlutusV3

/** Compiles [[VaultValidator]] to a Plutus V3 script. */
object VaultContract {
    private given Options = Options.release

    /** Release build: smallest script, no error traces. This is the script used on-chain. */
    lazy val compiled = PlutusV3.compile(VaultValidator.validate)
}
