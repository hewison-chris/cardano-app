port module Main exposing (main)

{-| Time-locked vault dApp.

The Elm app never touches CBOR or keys. It asks the Scala backend to build balanced, unsigned
transactions. Signing is done either via CIP-30 browser wallet ports or via dev party signers
in emulator mode.

-}

import Api exposing (Account, Config, DevWallet, UnsignedTx, Vault)
import Browser
import DateTime
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onCheck, onClick, onInput, onSubmit)
import Json.Decode as D
import Process
import Task
import Time



-- PORTS (implemented in public/wallet.js)


port detectWallets : () -> Cmd msg


port connectWallet : String -> Cmd msg


port disconnectWallet : () -> Cmd msg


port signTx : String -> Cmd msg


port walletsDetected : (D.Value -> msg) -> Sub msg


port walletConnected : (D.Value -> msg) -> Sub msg


port txSigned : (String -> msg) -> Sub msg


port walletError : (String -> msg) -> Sub msg



-- MODEL


type alias Flags =
    { now : Int
    , tzOffset : Int
    }


type alias WalletInfo =
    { id : String
    , name : String
    , icon : String
    }


type alias Wallet =
    { id : String
    , name : String
    , networkId : Int
    , address : String
    , isDev : Bool
    }


type Action
    = Lock
    | Claim Vault
    | Cancel Vault


type Stage
    = Building Action
    | Signing Action UnsignedTx
    | Submitting Action UnsignedTx


type Notice
    = NoNotice
    | Failure String
    | Submitted String String


type alias LockForm =
    { beneficiary : String
    , ada : String
    , deadline : String
    }


type Role
    = Owner
    | Beneficiary
    | OwnerAndBeneficiary
    | Stranger


type alias Model =
    { config : Maybe Config
    , wallets : List WalletInfo
    , walletsScanned : Bool
    , wallet : Maybe Wallet
    , account : Maybe Account
    , vaults : Maybe (List Vault)
    , showAll : Bool
    , form : LockForm
    , stage : Maybe Stage
    , notice : Notice
    , now : Time.Posix
    , zone : Time.Zone
    , tzOffset : Int
    , virtualTimeOffset : Int
    }


init : Flags -> ( Model, Cmd Msg )
init flags =
    let
        zone =
            Time.customZone (negate flags.tzOffset) []
    in
    ( { config = Nothing
      , wallets = []
      , walletsScanned = False
      , wallet = Nothing
      , account = Nothing
      , vaults = Nothing
      , showAll = False
      , form = { beneficiary = "", ada = "10", deadline = defaultDeadline zone flags.now (60 * 60 * 1000) }
      , stage = Nothing
      , notice = NoNotice
      , now = Time.millisToPosix flags.now
      , zone = zone
      , tzOffset = flags.tzOffset
      , virtualTimeOffset = 0
      }
    , Cmd.batch [ Api.getConfig GotConfig, Api.getVaults GotVaults, detectWallets () ]
    )


defaultDeadline : Time.Zone -> Int -> Int -> String
defaultDeadline zone now delta =
    -- Round to the next whole minute: the input has minute precision
    DateTime.toInputValue zone (Time.millisToPosix (((now + delta) // 60000 + 1) * 60000))



-- UPDATE


type Msg
    = GotConfig (Result String Config)
    | GotVaults (Result String (List Vault))
    | GotAccount (Result String Account)
    | WalletsDetected D.Value
    | ConnectClicked String
    | SelectDevWallet DevWallet
    | DisconnectClicked
    | WalletConnected D.Value
    | WalletError String
    | SetBeneficiary String
    | SetAda String
    | SetDeadline String
    | DeadlineIn Int
    | UseMyAddress
    | UseDevAddress String
    | SetShowAll Bool
    | Start Action
    | GotUnsigned (Result String UnsignedTx)
    | TxSigned String
    | GotSubmitted (Result String String)
    | Tick Time.Posix
    | Refresh
    | DismissNotice
    | FastForward Int
    | GotFastForward (Result String Int)
    | FaucetClicked
    | GotFaucet (Result String Float)


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        GotConfig (Ok config) ->
            let
                realNow =
                    Time.posixToMillis model.now

                virtualOffset =
                    if config.isEmulator && config.currentTime > 0 then
                        config.currentTime - realNow

                    else
                        0

                -- If we are in emulator mode and have dev wallets, auto-select Alice if not connected
                ( newWallet, cmd ) =
                    if config.isEmulator && model.wallet == Nothing then
                        case config.devWallets of
                            first :: _ ->
                                ( Just
                                    { id = first.name
                                    , name = "Simulated " ++ first.name
                                    , networkId = config.networkId
                                    , address = first.address
                                    , isDev = True
                                    }
                                , Api.getAccount first.address GotAccount
                                )

                            [] ->
                                ( Nothing, Cmd.none )

                    else
                        ( model.wallet, Cmd.none )
            in
            ( { model | config = Just config, wallet = newWallet, virtualTimeOffset = virtualOffset }, cmd )

        GotConfig (Err e) ->
            ( { model | notice = Failure ("Backend unavailable: " ++ e) }, Cmd.none )

        GotVaults result ->
            case result of
                Ok vaults ->
                    ( { model | vaults = Just vaults }, Cmd.none )

                Err e ->
                    ( { model | notice = Failure ("Could not load vaults: " ++ e) }, Cmd.none )

        GotAccount result ->
            case result of
                Ok account ->
                    ( { model | account = Just account }, Cmd.none )

                Err e ->
                    ( { model | notice = Failure e }, Cmd.none )

        WalletsDetected value ->
            ( { model
                | wallets = D.decodeValue (D.list walletInfoDecoder) value |> Result.withDefault []
                , walletsScanned = True
              }
            , Cmd.none
            )

        ConnectClicked id ->
            ( { model | notice = NoNotice }, connectWallet id )

        SelectDevWallet dev ->
            let
                w =
                    { id = dev.name
                    , name = "Simulated " ++ dev.name
                    , networkId = 0
                    , address = dev.address
                    , isDev = True
                    }
            in
            ( { model | wallet = Just w, account = Nothing, notice = NoNotice }
            , Api.getAccount dev.address GotAccount
            )

        DisconnectClicked ->
            ( { model | wallet = Nothing, account = Nothing, stage = Nothing }, disconnectWallet () )

        WalletConnected value ->
            case D.decodeValue walletDecoder value of
                Ok wallet ->
                    case model.config of
                        Just config ->
                            if not config.isEmulator && config.networkId /= wallet.networkId then
                                ( { model | notice = Failure ("Your wallet is on another network. Switch it to " ++ config.network ++ ".") }
                                , disconnectWallet ()
                                )

                            else
                                ( { model | wallet = Just wallet, account = Nothing }, Api.getAccount wallet.address GotAccount )

                        Nothing ->
                            ( { model | wallet = Just wallet, account = Nothing }, Api.getAccount wallet.address GotAccount )

                Err e ->
                    ( { model | notice = Failure (D.errorToString e) }, Cmd.none )

        WalletError e ->
            ( { model | stage = Nothing, notice = Failure e }, Cmd.none )

        SetBeneficiary s ->
            updateForm model (\f -> { f | beneficiary = s })

        SetAda s ->
            updateForm model (\f -> { f | ada = s })

        SetDeadline s ->
            updateForm model (\f -> { f | deadline = s })

        DeadlineIn delta ->
            let
                nowMillis =
                    Time.posixToMillis model.now + model.virtualTimeOffset
            in
            updateForm model (\f -> { f | deadline = defaultDeadline model.zone nowMillis delta })

        UseMyAddress ->
            case model.account of
                Just account ->
                    updateForm model (\f -> { f | beneficiary = account.address })

                Nothing ->
                    ( model, Cmd.none )

        UseDevAddress addr ->
            updateForm model (\f -> { f | beneficiary = addr })

        SetShowAll b ->
            ( { model | showAll = b }, Cmd.none )

        Start action ->
            case model.wallet of
                Nothing ->
                    ( { model | notice = Failure "Connect a wallet first" }, Cmd.none )

                Just wallet ->
                    case buildRequest model wallet action of
                        Ok cmd ->
                            ( { model | stage = Just (Building action), notice = NoNotice }, cmd )

                        Err e ->
                            ( { model | notice = Failure e }, Cmd.none )

        GotUnsigned result ->
            case ( model.stage, model.wallet, result ) of
                ( Just (Building action), Just wallet, Ok utx ) ->
                    if wallet.isDev then
                        -- Auto-sign using dev account
                        ( { model | stage = Just (Signing action utx) }
                        , Api.devSign utx.tx wallet.id (\res ->
                            case res of
                                Ok witness ->
                                    TxSigned witness

                                Err err ->
                                    WalletError err
                          )
                        )

                    else
                        ( { model | stage = Just (Signing action utx) }, signTx utx.tx )

                ( _, _, Err e ) ->
                    ( { model | stage = Nothing, notice = Failure e }, Cmd.none )

                _ ->
                    ( model, Cmd.none )

        TxSigned witness ->
            case model.stage of
                Just (Signing action utx) ->
                    ( { model | stage = Just (Submitting action utx) }, Api.submit utx.tx witness GotSubmitted )

                _ ->
                    ( model, Cmd.none )

        GotSubmitted result ->
            case ( model.stage, result ) of
                ( Just (Submitting action _), Ok txHash ) ->
                    let
                        form =
                            model.form
                    in
                    ( { model
                        | stage = Nothing
                        , notice = Submitted (describe action) txHash
                        , form =
                            if action == Lock then
                                { form | beneficiary = "" }

                            else
                                form
                      }
                    , Cmd.batch [ refreshCmd model, delayed 1000 Refresh ]
                    )

                ( _, Err e ) ->
                    ( { model | stage = Nothing, notice = Failure e }, Cmd.none )

                _ ->
                    ( model, Cmd.none )

        Tick now ->
            ( { model | now = now }, Cmd.none )

        Refresh ->
            ( model, refreshCmd model )

        DismissNotice ->
            ( { model | notice = NoNotice }, Cmd.none )

        FastForward seconds ->
            ( model, Api.advanceTime seconds GotFastForward )

        GotFastForward (Ok newTime) ->
            let
                realNow =
                    Time.posixToMillis model.now

                offset =
                    newTime - realNow
            in
            ( { model | virtualTimeOffset = offset, notice = NoNotice }
            , Cmd.batch [ refreshCmd model, Api.getConfig GotConfig ]
            )

        GotFastForward (Err e) ->
            ( { model | notice = Failure ("Could not advance emulator time: " ++ e) }, Cmd.none )

        FaucetClicked ->
            case model.wallet of
                Just wallet ->
                    ( model, Api.fundAddress wallet.address GotFaucet )

                Nothing ->
                    ( model, Cmd.none )

        GotFaucet (Ok ada) ->
            ( { model | notice = Submitted ("Funded " ++ String.fromFloat ada ++ " ADA from faucet") "faucet" }
            , refreshCmd model
            )

        GotFaucet (Err e) ->
            ( { model | notice = Failure ("Faucet error: " ++ e) }, Cmd.none )


updateForm : Model -> (LockForm -> LockForm) -> ( Model, Cmd Msg )
updateForm model f =
    ( { model | form = f model.form }, Cmd.none )


refreshCmd : Model -> Cmd Msg
refreshCmd model =
    Cmd.batch
        [ Api.getVaults GotVaults
        , case model.wallet of
            Just wallet ->
                Api.getAccount wallet.address GotAccount

            Nothing ->
                Cmd.none
        ]


delayed : Float -> Msg -> Cmd Msg
delayed ms msg =
    Process.sleep ms |> Task.perform (\_ -> msg)


buildRequest : Model -> Wallet -> Action -> Result String (Cmd Msg)
buildRequest model wallet action =
    case action of
        Lock ->
            let
                beneficiary =
                    String.trim model.form.beneficiary

                nowMillis =
                    Time.posixToMillis model.now + model.virtualTimeOffset
            in
            case ( String.toFloat (String.trim model.form.ada), DateTime.fromInputValue model.tzOffset model.form.deadline ) of
                ( Nothing, _ ) ->
                    Err "Enter the amount of ADA to lock"

                ( _, Nothing ) ->
                    Err "Pick a deadline"

                ( Just ada, Just deadline ) ->
                    if ada < 2 then
                        Err "Lock at least 2 ADA"

                    else if String.isEmpty beneficiary then
                        Err "Enter the beneficiary's address"

                    else if deadline <= nowMillis then
                        Err "The deadline must be in the future"

                    else
                        Ok
                            (Api.buildLock
                                { address = wallet.address
                                , beneficiary = beneficiary
                                , lovelace = round (ada * 1000000)
                                , deadline = deadline
                                }
                                GotUnsigned
                            )

        Claim vault ->
            Ok (Api.buildClaim wallet.address vault GotUnsigned)

        Cancel vault ->
            Ok (Api.buildCancel wallet.address vault GotUnsigned)


describe : Action -> String
describe action =
    case action of
        Lock ->
            "Funds locked"

        Claim vault ->
            "Claimed " ++ formatAda vault.lovelace

        Cancel vault ->
            "Cancelled vault, " ++ formatAda vault.lovelace ++ " returned"



-- DECODERS


walletInfoDecoder : D.Decoder WalletInfo
walletInfoDecoder =
    D.map3 WalletInfo
        (D.field "id" D.string)
        (D.field "name" D.string)
        (D.oneOf [ D.field "icon" D.string, D.succeed "" ])


walletDecoder : D.Decoder Wallet
walletDecoder =
    D.map5 Wallet
        (D.field "id" D.string)
        (D.field "name" D.string)
        (D.field "networkId" D.int)
        (D.field "address" D.string)
        (D.succeed False)



-- SUBSCRIPTIONS


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.batch
        [ walletsDetected WalletsDetected
        , walletConnected WalletConnected
        , txSigned TxSigned
        , walletError WalletError
        , Time.every 1000 Tick
        , Time.every 20000 (\_ -> Refresh)
        ]



-- VIEW


view : Model -> Html Msg
view model =
    div [ class "page" ]
        [ viewHeader model
        , viewEmulatorBanner model
        , viewNotice model
        , main_ [ class "grid" ]
            [ viewLockCard model
            , viewVaultsCard model
            ]
        , viewFooter model
        ]


viewHeader : Model -> Html Msg
viewHeader model =
    header [ class "header" ]
        [ div [ class "brand" ]
            [ span [ class "logo" ] [ text "⏳" ]
            , div []
                [ h1 [] [ text "Cardano Time Vault" ]
                , p [ class "muted" ] [ text "Lock ADA for someone until a deadline. Smart contract written in Scalus." ]
                ]
            ]
        , div [ class "wallet-area" ]
            [ case model.config of
                Just config ->
                    span
                        [ class
                            (if config.isEmulator then
                                "badge emulator"

                             else
                                "badge"
                            )
                        ]
                        [ text config.network ]

                Nothing ->
                    text ""
            , viewWallet model
            ]
        ]


viewEmulatorBanner : Model -> Html Msg
viewEmulatorBanner model =
    case model.config of
        Just config ->
            if config.isEmulator then
                div [ class "emulator-panel" ]
                    [ div [ class "emulator-header" ]
                        [ span [ class "emulator-tag" ] [ text "⚡ LOCAL EMULATOR ACTIVE" ]
                        , span [ class "emulator-time" ]
                            [ text
                                ("Virtual Clock: "
                                    ++ DateTime.format model.zone
                                        (Time.millisToPosix (Time.posixToMillis model.now + model.virtualTimeOffset))
                                )
                            ]
                        , div [ class "emulator-actions" ]
                            [ text "Fast-forward: "
                            , button [ class "btn chip", onClick (FastForward 300) ] [ text "+5 min" ]
                            , button [ class "btn chip", onClick (FastForward 3600) ] [ text "+1 hour" ]
                            , button [ class "btn chip", onClick (FastForward 86400) ] [ text "+1 day" ]
                            ]
                        ]
                    , if not (List.isEmpty config.devWallets) then
                        div [ class "dev-wallets" ]
                            [ span [ class "small muted" ] [ text "Switch test wallet: " ]
                            , div [ class "chips" ]
                                (List.map
                                    (\dev ->
                                        let
                                            isSelected =
                                                case model.wallet of
                                                    Just w ->
                                                        w.id == dev.name

                                                    Nothing ->
                                                        False
                                        in
                                        button
                                            [ class
                                                (if isSelected then
                                                    "chip active"

                                                 else
                                                    "chip"
                                                )
                                            , onClick (SelectDevWallet dev)
                                            ]
                                            [ text dev.name ]
                                    )
                                    config.devWallets
                                )
                            ]

                      else
                        text ""
                    ]

            else
                text ""

        Nothing ->
            text ""


viewWallet : Model -> Html Msg
viewWallet model =
    case model.wallet of
        Just wallet ->
            div [ class "wallet connected" ]
                [ div [ class "wallet-info" ]
                    [ div [ class "wallet-name" ] [ text wallet.name ]
                    , div [ class "muted small" ] [ text (shorten wallet.address) ]
                    ]
                , div [ class "wallet-balance-box" ]
                    [ span [ class "balance-title" ] [ text "Balance" ]
                    , span [ class "balance-amount" ]
                        [ text
                            (case model.account of
                                Just account ->
                                    formatAda account.lovelace

                                Nothing ->
                                    "Loading…"
                            )
                        ]
                    ]
                , if model.config |> Maybe.map .isEmulator |> Maybe.withDefault False then
                    button [ class "btn ghost", onClick FaucetClicked, title "Add 1,000 ADA to this wallet" ] [ text "+ Faucet" ]

                  else
                    text ""
                , button [ class "btn ghost", onClick DisconnectClicked ] [ text "Disconnect" ]
                ]

        Nothing ->
            div [ class "wallet-list" ]
                (List.map
                    (\w ->
                        button [ class "btn wallet-btn", onClick (ConnectClicked w.id) ]
                            [ if String.isEmpty w.icon then
                                text ""

                              else
                                img [ src w.icon, alt "" ] []
                            , text w.name
                            ]
                    )
                    model.wallets
                )


viewNotice : Model -> Html Msg
viewNotice model =
    let
        progress =
            Maybe.map stageMessage model.stage
    in
    case ( progress, model.notice ) of
        ( Just message, _ ) ->
            div [ class "notice info" ] [ span [ class "spinner" ] [], text message ]

        ( Nothing, Failure e ) ->
            div [ class "notice error" ]
                [ span [] [ text e ], button [ class "close", onClick DismissNotice ] [ text "×" ] ]

        ( Nothing, Submitted description txHash ) ->
            div [ class "notice success" ]
                [ span []
                    [ text (description ++ ". ")
                    , if txHash /= "faucet" then
                        explorerLink model ("/transaction/" ++ txHash) (shorten txHash)

                      else
                        text ""
                    ]
                , button [ class "close", onClick DismissNotice ] [ text "×" ]
                ]

        ( Nothing, NoNotice ) ->
            text ""


stageMessage : Stage -> String
stageMessage stage =
    case stage of
        Building _ ->
            "Building transaction…"

        Signing _ utx ->
            "Waiting for signature (fee " ++ formatAda utx.fee ++ ")…"

        Submitting _ _ ->
            "Submitting transaction to ledger…"


viewLockCard : Model -> Html Msg
viewLockCard model =
    section [ class "card" ]
        [ div [ class "card-header-row" ]
            [ h2 [] [ text "Lock funds" ]
            , case model.account of
                Just account ->
                    div [ class "card-balance-tag" ]
                        [ span [ class "muted small" ] [ text "Wallet Balance: " ]
                        , strong [ class "balance-highlight" ] [ text (formatAda account.lovelace) ]
                        ]

                Nothing ->
                    text ""
            ]
        , Html.form [ onSubmit (Start Lock) ]
            [ label []
                [ text "Beneficiary address"
                , div [ class "row" ]
                    [ input
                        [ type_ "text"
                        , placeholder "addr_test1…"
                        , value model.form.beneficiary
                        , onInput SetBeneficiary
                        , spellcheck False
                        ]
                        []
                    , button
                        [ type_ "button"
                        , class "btn ghost"
                        , onClick UseMyAddress
                        , disabled (model.account == Nothing)
                        ]
                        [ text "Me" ]
                    ]
                , case model.config of
                    Just config ->
                        if config.isEmulator && not (List.isEmpty config.devWallets) then
                            div [ class "chips", style "margin-top" "0.4rem" ]
                                (List.map
                                    (\dev ->
                                        button
                                            [ type_ "button"
                                            , class "chip"
                                            , onClick (UseDevAddress dev.address)
                                            ]
                                            [ text ("Send to " ++ dev.name) ]
                                    )
                                    config.devWallets
                                )

                        else
                            text ""

                    Nothing ->
                        text ""
                ]
            , label []
                [ div [ class "label-with-balance" ]
                    [ span [] [ text "Amount (ADA)" ]
                    , case model.account of
                        Just account ->
                            span [ class "muted small" ]
                                [ text ("Available: " ++ formatAda account.lovelace) ]

                        Nothing ->
                            text ""
                    ]
                , input [ type_ "number", Html.Attributes.min "2", step "0.000001", value model.form.ada, onInput SetAda ] []
                ]
            , label []
                [ text "Unlocks at"
                , input [ type_ "datetime-local", value model.form.deadline, onInput SetDeadline ] []
                , div [ class "chips" ]
                    [ chip "+5 min" (5 * 60 * 1000)
                    , chip "+1 hour" (60 * 60 * 1000)
                    , chip "+1 day" (24 * 60 * 60 * 1000)
                    , chip "+1 week" (7 * 24 * 60 * 60 * 1000)
                    ]
                ]
            , button
                [ type_ "submit", class "btn primary", disabled (model.wallet == Nothing || model.stage /= Nothing) ]
                [ text "Lock ADA" ]
            , p [ class "muted small" ]
                [ text "Before the deadline you can cancel and get the funds back. From the deadline on, only the beneficiary can claim them." ]
            ]
        ]


chip : String -> Int -> Html Msg
chip label delta =
    button [ type_ "button", class "chip", onClick (DeadlineIn delta) ] [ text label ]


viewVaultsCard : Model -> Html Msg
viewVaultsCard model =
    let
        withRoles =
            Maybe.withDefault [] model.vaults
                |> List.map (\v -> ( v, roleOf model.account v ))

        shown =
            if model.showAll then
                withRoles

            else
                List.filter (\( _, role ) -> role /= Stranger) withRoles
    in
    section [ class "card" ]
        [ div [ class "card-title" ]
            [ h2 [] [ text "Vaults" ]
            , div [ class "row small" ]
                [ label [ class "inline" ]
                    [ input [ type_ "checkbox", checked model.showAll, onCheck SetShowAll ] []
                    , text (" Show all (" ++ String.fromInt (List.length withRoles) ++ ")")
                    ]
                , button [ class "btn ghost", onClick Refresh ] [ text "↻" ]
                ]
            ]
        , case model.vaults of
            Nothing ->
                p [ class "muted" ] [ text "Loading…" ]

            Just _ ->
                if List.isEmpty shown then
                    p [ class "muted" ]
                        [ text
                            (if model.wallet == Nothing then
                                "Connect or pick a test wallet to see vaults."

                             else
                                "No vaults for this wallet yet."
                            )
                        ]

                else
                    ul [ class "vaults" ] (List.map (viewVault model) shown)
        ]


viewVault : Model -> ( Vault, Role ) -> Html Msg
viewVault model ( vault, role ) =
    let
        now =
            Time.posixToMillis model.now + model.virtualTimeOffset

        remaining =
            vault.deadline - now

        unlocked =
            remaining <= 0

        isBeneficiary =
            role == Beneficiary || role == OwnerAndBeneficiary

        isOwner =
            role == Owner || role == OwnerAndBeneficiary

        busy =
            model.stage /= Nothing
    in
    li [ class "vault" ]
        [ div [ class "vault-main" ]
            [ div [ class "amount" ] [ text (formatAda vault.lovelace) ]
            , div [ class "muted small" ]
                [ text (roleText role ++ " · ")
                , explorerLink model ("/transaction/" ++ vault.txHash) (shorten vault.txHash ++ "#" ++ String.fromInt vault.index)
                ]
            ]
        , div [ class "vault-time" ]
            [ div
                [ class
                    (if unlocked then
                        "status unlocked"

                     else
                        "status locked"
                    )
                ]
                [ text
                    (if unlocked then
                        "Unlocked " ++ humanize (negate remaining) ++ " ago"

                     else
                        "Unlocks in " ++ humanize remaining
                    )
                ]
            , div [ class "muted small" ] [ text (DateTime.format model.zone (Time.millisToPosix vault.deadline)) ]
            ]
        , div [ class "vault-actions" ]
            [ if isBeneficiary && unlocked then
                button [ class "btn primary", disabled busy, onClick (Start (Claim vault)) ] [ text "Claim" ]

              else
                text ""
            , if isOwner && remaining > 10000 then
                button [ class "btn danger", disabled busy, onClick (Start (Cancel vault)) ] [ text "Cancel" ]

              else
                text ""
            ]
        ]


viewFooter : Model -> Html Msg
viewFooter model =
    footer [ class "footer muted small" ]
        (case model.config of
            Just config ->
                [ text "Contract "
                , if String.isEmpty config.explorer then
                    text (shorten config.scriptAddress)

                  else
                    explorerLink model ("/address/" ++ config.scriptAddress) (shorten config.scriptAddress)
                , text (" · script hash " ++ shorten config.scriptHash ++ " · " ++ String.fromInt config.scriptSize ++ " bytes · Plutus V3, compiled by Scalus")
                ]

            Nothing ->
                [ text "Connecting to backend…" ]
        )


explorerLink : Model -> String -> String -> Html Msg
explorerLink model path label =
    case model.config of
        Just config ->
            if String.isEmpty config.explorer then
                span [] [ text label ]

            else
                a [ href (config.explorer ++ path), target "_blank", rel "noopener" ] [ text label ]

        Nothing ->
            text label



-- HELPERS


roleOf : Maybe Account -> Vault -> Role
roleOf account vault =
    case account of
        Nothing ->
            Stranger

        Just a ->
            case ( a.pkh == vault.owner, a.pkh == vault.beneficiary ) of
                ( True, True ) ->
                    OwnerAndBeneficiary

                ( True, False ) ->
                    Owner

                ( False, True ) ->
                    Beneficiary

                ( False, False ) ->
                    Stranger


roleText : Role -> String
roleText role =
    case role of
        Owner ->
            "You locked this"

        Beneficiary ->
            "For you"

        OwnerAndBeneficiary ->
            "Locked by you, for you"

        Stranger ->
            "Other"


formatAda : Int -> String
formatAda lovelace =
    let
        whole =
            lovelace // 1000000

        fraction =
            String.padLeft 6 '0' (String.fromInt (modBy 1000000 lovelace))
                |> trimTrailingZeros
    in
    if String.isEmpty fraction then
        String.fromInt whole ++ " ₳"

    else
        String.fromInt whole ++ "." ++ fraction ++ " ₳"


trimTrailingZeros : String -> String
trimTrailingZeros s =
    if String.endsWith "0" s then
        trimTrailingZeros (String.dropRight 1 s)

    else
        s


humanize : Int -> String
humanize ms =
    let
        s =
            ms // 1000

        unit n suffix =
            String.fromInt n ++ suffix
    in
    if s < 60 then
        unit s "s"

    else if s < 3600 then
        unit (s // 60) "m " ++ unit (modBy 60 s) "s"

    else if s < 86400 then
        unit (s // 3600) "h " ++ unit (modBy 3600 s // 60) "m"

    else
        unit (s // 86400) "d " ++ unit (modBy 86400 s // 3600) "h"


shorten : String -> String
shorten s =
    if String.length s <= 20 then
        s

    else
        String.left 12 s ++ "…" ++ String.right 6 s



-- MAIN


main : Program Flags Model Msg
main =
    Browser.element
        { init = init
        , update = update
        , view = view
        , subscriptions = subscriptions
        }
