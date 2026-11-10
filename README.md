# Cardano Time-Locked Vault dApp

A complete Cardano decentralized application featuring:
- **Smart Contract**: Plutus V3 spending validator written in Scala 3 using **[Scalus](https://scalus.org)** (v1.5.0).
- **Backend**: Scala 3 service using **Cask** and Scalus `TxBuilder` + `BlockfrostProvider` for on-chain queries, slot calculations, and fee-balanced unsigned transaction construction.
- **Frontend**: Clean single-page application written in **Elm 0.19.1**, interacting with CIP-30 browser wallets (Lace, Eternl, Nami, Typhon) via Elm ports and REST endpoints.

---

## Architecture Overview

```
                      +-----------------------------+
                      |     Elm Frontend (SPA)      |
                      |  - State management (TEA)   |
                      |  - Form inputs & validation |
                      |  - Real-time countdowns     |
                      +--------------+--------------+
                                     |
               (CIP-30 Ports)        | (HTTP REST)
        +----------------------------+----------------------------+
        |                                                         |
        v                                                         v
+-----------------------+                         +-------------------------------+
|  Browser Wallet       |                         |  Scala 3 Backend (Cask)       |
|  (Lace/Eternl/Nami)   |                         |  - Reads chain via Blockfrost |
|  - Signs unsigned tx  | -- (Signed Witness Set) -> - Builds balanced tx via     |
|    with private keys  |                         |    Scalus TxBuilder           |
+-----------------------+                         |  - Merges witnesses & submits |
                                                  +---------------+---------------+
                                                                  |
                                                           (Blockfrost API)
                                                                  v
                                                  +-------------------------------+
                                                  |     Cardano Blockchain        |
                                                  |  (Preprod / Preview / Main)   |
                                                  |  - Locked UTxOs validated by  |
                                                  |    Scalus Plutus V3 Validator |
                                                  +-------------------------------+
```

### On-Chain Smart Contract (`VaultValidator.scala`)
- **Datum**:
  - `owner`: Payment key hash of the funder
  - `beneficiary`: Payment key hash of the recipient
  - `deadline`: POSIX timestamp in milliseconds
- **Redeemer Actions**:
  - `Claim`: Beneficiary withdraws funds at or after the deadline (`tx.validFrom >= deadline`).
  - `Cancel`: Owner cancels and reclaims funds strictly before the deadline (`tx.validTo <= deadline`).
- Compiled to Plutus V3 bytecode via `@Compile` and `PlutusV3.compile(VaultValidator.validate)`.

---

## Project Structure

```
.
├── build.sbt                                 # Multi-project SBT configuration
├── contract/                                 # On-chain validator & off-chain builders
│   └── src/
│       ├── main/scala/vault/
│       │   ├── VaultValidator.scala          # Scalus on-chain validator
│       │   ├── VaultContract.scala           # Plutus V3 compilation
│       │   └── offchain/
│       │       ├── Cip30.scala               # CBOR & CIP-30 witness handling
│       │       └── VaultTransactions.scala   # Scalus TxBuilder transaction creator
│       └── test/scala/vault/
│           └── VaultValidatorTest.scala      # 15 tests using Scalus local Emulator
├── backend/                                  # REST API server
│   └── src/
│       ├── main/scala/vault/server/
│       │   ├── VaultService.scala            # Blockfrost queries & tx orchestration
│       │   └── VaultServer.scala             # Cask HTTP routes & static file server
│       └── test/scala/vault/server/
│           └── VaultServiceTest.scala        # Backend tests using Scalus Emulator
└── frontend/                                 # Elm frontend application
    ├── elm.json
    ├── package.json
    ├── src/
    │   ├── Main.elm                          # Elm application & port subscriptions
    │   ├── Api.elm                           # HTTP decoders & API client
    │   └── DateTime.elm                      # Date/time handling & conversions
    └── public/
        ├── index.html                        # HTML shell
        ├── wallet.js                         # CIP-30 wallet bridge
        └── style.css                         # Dark theme styling
```

---

## Prerequisites

- **Java JDK 17+** (JDK 21 recommended)
- **SBT** (v1.10+)
- **Node.js** (v18+) & **npm**

---

## Getting Started

### 1. Run Unit & Validator Tests
All on-chain logic and off-chain transaction creation are verified with Scalus's in-memory Cardano Ledger Emulator:

```bash
sbt test
```

This executes:
- **`VaultValidatorTest`**: 15 tests covering datum creation, validity windows, signature verification, fee convergence, and negative/attack scenarios.
- **`VaultServiceTest`**: Tests the API service layer transaction generation and input verification against the emulator.

### 2. Build the Elm Frontend
Compile the Elm code into the static distribution file:

```bash
cd frontend
npm install
npm run build
cd ..
```

*(For development with Elm debugger, run `npm run dev`)*.

### 3. Run with the Local Emulator (No API Key Required!)
You can run the application immediately with an in-memory Cardano ledger emulator. This requires **no Blockfrost account, no network sync, and no testnet ADA**:

```bash
sbt "backend/run"
```

Open [http://localhost:8080](http://localhost:8080) in your browser:
- **Simulated Test Wallets**: Pre-funded dev accounts (**Alice**, **Bob**, **Eve**, **Dave**) with 10,000 ADA each. You can switch between them with one click right in the UI without installing any browser extensions.
- **Fast-Forward Virtual Time**: Buttons to jump `+5 min`, `+1 hour`, or `+1 day` into the future so you can test vault unlocking and claiming in real time without waiting!
- **Faucet for CIP-30 Wallets**: If you connect Lace, Eternl, or Nami in emulator mode, click **"+ Faucet"** to fund your wallet with 1,000 simulated ADA.

---

### 4. Optional: Run on Cardano Preprod or Mainnet
To connect to the public Cardano Preprod or Mainnet network via Blockfrost:

1. Copy the environment file:
   ```bash
   cp .env.example .env
   ```
2. Edit `.env` with your Blockfrost project key:
   ```env
   NETWORK=preprod
   BLOCKFROST_PROJECT_ID=preprodYourApiKeyHere
   PORT=8080
   ```
3. Run with the environment loaded:
   ```bash
   export $(cat .env | xargs)
   sbt "backend/run"
   ```

---

## Interacting with the dApp

1. **Connect Wallet**: Click your installed CIP-30 browser wallet (e.g. Lace, Eternl, Nami) in the top right corner. Make sure your wallet is set to the matching network (e.g., Preprod).
2. **Lock ADA**:
   - Enter the beneficiary address (or click **"Me"** to test self-vesting).
   - Enter the amount of ADA (minimum 2 ADA).
   - Select an unlock time or click one of the quick chips (`+5 min`, `+1 hour`, `+1 day`).
   - Click **Lock ADA** and sign the transaction in your wallet.
3. **Cancel Vault**: If you are the owner and the deadline has not arrived yet, click **Cancel** to refund the vault to your address.
4. **Claim Vault**: Once the deadline passes, the beneficiary will see an active **Claim** button to withdraw the locked ADA.

