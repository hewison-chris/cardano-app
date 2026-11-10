# Deployment Guide: Cardano Preprod, Free Scala Backend, and GitHub Pages

This guide walks you through deploying the complete decentralized application:

1. **Cardano Preprod Blockchain**: Deploying and referencing the Scalus Plutus V3 smart contract.
2. **Scala 3 Backend**: Deploying the Cask & Scalus service for free (on Render, Fly.io, or Koyeb).
3. **Elm Frontend**: Deploying the single-page application to GitHub Pages with automated CI/CD.

---

## Architecture Topology

```
+---------------------------------------------------------------------------------+
|                                 GitHub Pages                                    |
|                https://<your-username>.github.io/<repo-name>/                   |
|                        (Static Elm 0.19.1 Frontend)                             |
+---------------------------------------+-----------------------------------------+
                                        |
                 CIP-30 Ports           |  Cross-Origin HTTP REST (CORS enabled)
       +--------------------------------+--------------------------------+
       |                                                                 |
       v                                                                 v
+---------------+                                           +----------------------------+
| Browser Wallet|                                           |  Free Cloud Scala Service  |
| (Lace/Eternl) |                                           |  (Render / Fly.io / Koyeb) |
| - Signs tx on | --- Signed Witness Set (Hex CBOR) ------> |  - Scalus TxBuilder        |
|   Preprod     |                                           |  - BlockfrostProvider      |
+---------------+                                           +--------------+-------------+
                                                                           |
                                                                Submit Tx  |
                                                                           v
                                                            +----------------------------+
                                                            |  Cardano Preprod Testnet   |
                                                            |  - Script Address          |
                                                            |  - CIP-33 Reference Script |
                                                            +----------------------------+
```

---

## Phase 1: Deploying the Smart Contract to Cardano Preprod

In Cardano (unlike account-based chains such as Ethereum), Plutus scripts are **not** deployed as mutable stateful accounts. Instead:

- A validator's address is **deterministically derived** from the hash of its compiled Plutus Core bytecode:
  $$\text{Script Address} = \text{Address}(\text{hash}(\text{PlutusV3 Bytecode}))$$
- To spend a script UTxO, Cardano transactions can either:
  1. Attach the script bytecode directly inside the spending transaction's witness set (default, fully supported by our backend).
  2. Or reference an on-chain **Reference Script UTxO** (CIP-33), which drastically reduces transaction size and fees.

### 1.1 Export the Compiled Script & Address

You can view the compiled script hash and CBOR bytecode directly:

```bash
# Start backend in test environment or run the test suite
sbt "contract/test"
```

The compiled script parameters for this contract are:

- **Language**: Plutus V3 (Cardano Conway ledger era)
- **Script Hash**: `9cba87439b01f2c68f3b02406fb17990323862169a52d76a784723df`
- **Script Size**: `280` bytes (compact and gas-efficient)
- **Preprod Script Address**:
  ```text
  addr_test1wzwt4p6rnvql93508vpyqma30xgrywrzz6d994m20prj8hc4mftgl
  ```

### 1.2 (Optional) Deploy as an On-Chain Reference Script (CIP-33)

To publish the validator as a permanent reference script on Cardano Preprod:

1. Obtain a free **Blockfrost Preprod Project ID** from [https://blockfrost.io](https://blockfrost.io).
2. Use `cardano-cli`, Mesh, or a simple transaction to send a small amount of ADA (~15-20 ADA to cover min-UTxO storage) containing the script bytes as a reference script:
   ```bash
   cardano-cli conway transaction build \
     --testnet-magic 1 \
     --tx-in <YOUR_TX_IN> \
     --tx-out <STORAGE_ADDRESS>+20000000 \
     --tx-out-reference-script-file vault-contract.plutus \
     --change-address <YOUR_ADDRESS> \
     --out-file deploy-ref-tx.raw
   ```
   _(Note: Reference scripts are an optional fee-optimization; the backend's `VaultTransactions` builder automatically attaches the script witness for spending if no reference UTxO is specified)._

---

## Phase 2: Deploying the Scala Backend for Free

The backend is packaged as a lightweight container using the included multi-stage [`Dockerfile`](file:///Users/chrishewison/HOME/cardano-app/Dockerfile).

### Option A: Deploy on Render.com (Recommended Free Tier)

[Render](https://render.com) provides a free Web Service tier with native Docker support and HTTPS out of the box:

1. Push your repository to GitHub.
2. Log into [Render Dashboard](https://dashboard.render.com).
3. Click **New +** $\rightarrow$ **Web Service**.
4. Connect your GitHub repository.
5. Configure the service:
   - **Name**: `cardano-vault-backend`
   - **Region**: Choose the closest region (e.g. Frankfurt, Oregon, Singapore)
   - **Language**: **Docker**
   - **Branch**: `main` (or `develop`)
   - **Instance Type**: **Free**
6. **Environment Variables**:
   Add the following variables in the Render dashboard:
   | Key | Value | Description |
   |---|---|---|
   | `NETWORK` | `preprod` | Cardano network |
   | `BLOCKFROST_PROJECT_ID` | `preprodXXXXXXXXXXXX` | Your Blockfrost API Key |
   | `HOST` | `0.0.0.0` | Listen interface |
   | `PORT` | `8080` | Service port |
7. Click **Create Web Service**.
8. Render will build the container using [`Dockerfile`](file:///Users/chrishewison/HOME/cardano-app/Dockerfile). Once live, note your public URL:
   ```text
   https://cardano-vault-backend.onrender.com
   ```
9. Verify the deployment:
   ```bash
   curl https://cardano-vault-backend.onrender.com/api/config
   ```

---

### Option B: Deploy on Fly.io (Free Allowance)

If you prefer the command line:

1. Install Fly CLI: `brew install flyctl` or `curl -L https://fly.io/install.sh | sh`
2. Authenticate: `fly auth login`
3. Launch the app from the project root:
   ```bash
   fly launch --no-deploy
   ```
4. Set the environment secrets:
   ```bash
   fly secrets set NETWORK=preprod BLOCKFROST_PROJECT_ID=preprodYourKeyHere
   ```
5. Deploy:
   ```bash
   fly deploy
   ```

---

### Option C: Deploy on Koyeb (Free Eco Tier)

1. Log into [Koyeb](https://www.koyeb.com).
2. Create an App $\rightarrow$ Source: **GitHub**.
3. Select this repository and pick **Dockerfile**.
4. Set Environment Variables:
   - `NETWORK=preprod`
   - `BLOCKFROST_PROJECT_ID=preprod...`
5. Click **Deploy**.

---

## Phase 3: Deploying the Elm Frontend to GitHub Pages

The Elm frontend is automated using GitHub Actions via [`.github/workflows/deploy.yml`](file:///Users/chrishewison/HOME/cardano-app/.github/workflows/deploy.yml).

### 3.1 Configure GitHub Repository Settings

1. Push your code to your GitHub repository:
   ```bash
   git add .
   git commit -m "Add production deployment configurations"
   git push origin main
   ```
2. In your GitHub repository:
   - Go to **Settings** $\rightarrow$ **Pages**.
   - Under **Build and deployment** $\rightarrow$ **Source**, choose **GitHub Actions**.

### 3.2 Connect the Frontend to Your Deployed Backend

To tell the Elm frontend where your deployed backend lives:

1. In GitHub, go to **Settings** $\rightarrow$ **Secrets and variables** $\rightarrow$ **Actions** $\rightarrow$ **Variables** tab.
2. Click **New repository variable**:
   - **Name**: `BACKEND_URL`
   - **Value**: Your backend's HTTPS URL (e.g., `https://cardano-vault-backend.onrender.com`)
3. Trigger the workflow:
   - Go to the **Actions** tab $\rightarrow$ **Deploy Elm Frontend to GitHub Pages** $\rightarrow$ Click **Run workflow**.

When the job completes, your application is live at:

```text
https://<your-github-username>.github.io/<your-repository-name>/
```

---

## Phase 4: Verification & End-to-End Walkthrough

1. **Open Your GitHub Pages Site**:
   Visit `https://<your-username>.github.io/<repo>/`.
2. **Check the Network Badge**:
   The top right badge will show **`PREPROD`** (loaded from `/api/config`).
3. **Connect Your Wallet**:
   - Make sure your Lace or Eternl wallet is set to **Preprod**.
   - Connect the wallet; your tADA testnet balance will display automatically.
4. **Lock Funds**:
   - Enter a beneficiary address (`addr_test1...`) or click **"Me"** to test with your own address.
   - Enter an amount (e.g. `10` ADA) and set an unlock time.
   - Click **Lock ADA** $\rightarrow$ sign the popup in your browser wallet.
5. **View On Cardanoscan Explorer**:
   - Once submitted, click the transaction hash link to inspect the on-chain UTxO and datum at:
     ```
     https://preprod.cardanoscan.io/transaction/<TX_HASH>
     ```
6. **Claim or Cancel**:
   - Once the deadline arrives, the beneficiary clicks **Claim** to withdraw the ADA.

---

## Production Security & Maintenance Checklist

- [x] **CORS Support**: Preflight `OPTIONS` and cross-origin headers are configured in `VaultServer.scala` to allow GitHub Pages requests.
- [x] **Safe Plutus V3 Rules**: Strict fee calculation, positive amount verification, single-input spend verification, and lower/upper bound time constraints.
- [x] **Cold Start on Free Tiers**: Free tiers on Render/Koyeb spin down after inactivity. A request might take 30–40 seconds to warm up the JVM if the service has been idle. (You can keep it warm using free uptime pingers like UptimeRobot targeting `/api/config`).
- [x] **Private Keys**: No private keys ever touch the backend. The Scala service is stateless with respect to secrets; only the user's browser wallet signs transactions.
