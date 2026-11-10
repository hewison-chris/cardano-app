module Api exposing
    ( Account
    , Config
    , DevWallet
    , UnsignedTx
    , Vault
    , advanceTime
    , buildCancel
    , buildClaim
    , buildLock
    , devSign
    , fundAddress
    , getAccount
    , getConfig
    , getVaults
    , submit
    )

{-| HTTP client for the Scala backend. Every call reports failures as a human readable `String`
(the backend answers errors with `{"error": "..."}`).
-}

import Http
import Json.Decode as D exposing (Decoder)
import Json.Encode as E



-- TYPES


type alias DevWallet =
    { name : String
    , address : String
    , pkh : String
    }


type alias Config =
    { network : String
    , networkId : Int
    , isEmulator : Bool
    , currentSlot : Int
    , currentTime : Int
    , scriptAddress : String
    , scriptHash : String
    , scriptSize : Int
    , explorer : String
    , devWallets : List DevWallet
    }


type alias Account =
    { address : String
    , pkh : String
    , lovelace : Int
    }


type alias Vault =
    { txHash : String
    , index : Int
    , lovelace : Int
    , owner : String
    , beneficiary : String
    , deadline : Int
    }


{-| A balanced transaction (hex CBOR) waiting for the wallet's signature.
-}
type alias UnsignedTx =
    { tx : String
    , txHash : String
    , fee : Int
    }



-- REQUESTS


getConfig : (Result String Config -> msg) -> Cmd msg
getConfig toMsg =
    Http.get { url = "/api/config", expect = expectJson toMsg configDecoder }


getVaults : (Result String (List Vault) -> msg) -> Cmd msg
getVaults toMsg =
    Http.get { url = "/api/vaults", expect = expectJson toMsg (D.list vaultDecoder) }


getAccount : String -> (Result String Account -> msg) -> Cmd msg
getAccount address toMsg =
    post "/api/address" [ ( "address", E.string address ) ] (expectJson toMsg accountDecoder)


buildLock :
    { address : String, beneficiary : String, lovelace : Int, deadline : Int }
    -> (Result String UnsignedTx -> msg)
    -> Cmd msg
buildLock req toMsg =
    post "/api/tx/lock"
        [ ( "address", E.string req.address )
        , ( "beneficiary", E.string req.beneficiary )
        , ( "lovelace", E.int req.lovelace )
        , ( "deadline", E.int req.deadline )
        ]
        (expectJson toMsg unsignedDecoder)


buildClaim : String -> Vault -> (Result String UnsignedTx -> msg) -> Cmd msg
buildClaim address vault toMsg =
    post "/api/tx/claim" (vaultRef address vault) (expectJson toMsg unsignedDecoder)


buildCancel : String -> Vault -> (Result String UnsignedTx -> msg) -> Cmd msg
buildCancel address vault toMsg =
    post "/api/tx/cancel" (vaultRef address vault) (expectJson toMsg unsignedDecoder)


{-| Send the unsigned transaction and the wallet's witness set; returns the transaction hash.
-}
submit : String -> String -> (Result String String -> msg) -> Cmd msg
submit tx witness toMsg =
    post "/api/tx/submit"
        [ ( "tx", E.string tx ), ( "witness", E.string witness ) ]
        (expectJson toMsg (D.field "txHash" D.string))


{-| Fast-forward the in-memory emulator clock by `seconds`.
-}
advanceTime : Int -> (Result String Int -> msg) -> Cmd msg
advanceTime seconds toMsg =
    post "/api/emulator/advance-time"
        [ ( "seconds", E.int seconds ) ]
        (expectJson toMsg (D.field "currentTime" intLike))


{-| Fund an address with simulated ADA from the emulator faucet.
-}
fundAddress : String -> (Result String Float -> msg) -> Cmd msg
fundAddress address toMsg =
    post "/api/emulator/fund"
        [ ( "address", E.string address ), ( "ada", E.int 1000 ) ]
        (expectJson toMsg (D.field "fundedAda" D.float))


{-| Sign a transaction using a pre-funded test party's private key in emulator mode.
-}
devSign : String -> String -> (Result String String -> msg) -> Cmd msg
devSign tx party toMsg =
    post "/api/emulator/dev-sign"
        [ ( "tx", E.string tx ), ( "party", E.string party ) ]
        (expectJson toMsg (D.field "witness" D.string))



-- HELPERS


vaultRef : String -> Vault -> List ( String, E.Value )
vaultRef address vault =
    [ ( "address", E.string address )
    , ( "txHash", E.string vault.txHash )
    , ( "index", E.int vault.index )
    ]


post : String -> List ( String, E.Value ) -> Http.Expect msg -> Cmd msg
post url fields expect =
    Http.post { url = url, body = Http.jsonBody (E.object fields), expect = expect }


expectJson : (Result String a -> msg) -> Decoder a -> Http.Expect msg
expectJson toMsg decoder =
    Http.expectStringResponse toMsg <|
        \response ->
            case response of
                Http.BadUrl_ url ->
                    Err ("Bad URL: " ++ url)

                Http.Timeout_ ->
                    Err "The server timed out"

                Http.NetworkError_ ->
                    Err "Cannot reach the server"

                Http.BadStatus_ meta body ->
                    case D.decodeString (D.field "error" D.string) body of
                        Ok message ->
                            Err message

                        Err _ ->
                            Err ("Server error " ++ String.fromInt meta.statusCode)

                Http.GoodStatus_ _ body ->
                    D.decodeString decoder body |> Result.mapError D.errorToString



-- DECODERS


{-| The backend sends large integers as JSON numbers that may be written as floats.
-}
intLike : Decoder Int
intLike =
    D.oneOf [ D.int, D.map round D.float ]


devWalletDecoder : Decoder DevWallet
devWalletDecoder =
    D.map3 DevWallet
        (D.field "name" D.string)
        (D.field "address" D.string)
        (D.field "pkh" D.string)


configDecoder : Decoder Config
configDecoder =
    D.map8
        (\net netId isEm slot t addr hash size ->
            \exp devs ->
                { network = net
                , networkId = netId
                , isEmulator = isEm
                , currentSlot = slot
                , currentTime = t
                , scriptAddress = addr
                , scriptHash = hash
                , scriptSize = size
                , explorer = exp
                , devWallets = devs
                }
        )
        (D.field "network" D.string)
        (D.field "networkId" intLike)
        (D.oneOf [ D.field "isEmulator" D.bool, D.succeed False ])
        (D.oneOf [ D.field "currentSlot" intLike, D.succeed 0 ])
        (D.oneOf [ D.field "currentTime" intLike, D.succeed 0 ])
        (D.field "scriptAddress" D.string)
        (D.field "scriptHash" D.string)
        (D.field "scriptSize" intLike)
        |> D.andThen
            (\fn ->
                D.map2 fn
                    (D.field "explorer" D.string)
                    (D.oneOf [ D.field "devWallets" (D.list devWalletDecoder), D.succeed [] ])
            )


accountDecoder : Decoder Account
accountDecoder =
    D.map3 Account
        (D.field "address" D.string)
        (D.field "pkh" D.string)
        (D.field "lovelace" intLike)


vaultDecoder : Decoder Vault
vaultDecoder =
    D.map6 Vault
        (D.field "txHash" D.string)
        (D.field "index" intLike)
        (D.field "lovelace" intLike)
        (D.field "owner" D.string)
        (D.field "beneficiary" D.string)
        (D.field "deadline" intLike)


unsignedDecoder : Decoder UnsignedTx
unsignedDecoder =
    D.map3 UnsignedTx
        (D.field "tx" D.string)
        (D.field "txHash" D.string)
        (D.field "fee" intLike)
