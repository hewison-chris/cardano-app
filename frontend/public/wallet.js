// CIP-30 wallet bridge for the Elm app.
// Elm sends commands through ports; this file talks to window.cardano.<wallet> and answers back.
(function () {
  "use strict";

  const STORAGE_KEY = "vault.wallet";

  function errorMessage(e) {
    if (!e) return "Unknown wallet error";
    if (typeof e === "string") return e;
    // CIP-30 errors look like { code, info } (APIError) or { code, info } (TxSignError)
    if (e.info) return "Wallet: " + e.info;
    if (e.message) return "Wallet: " + e.message;
    try {
      return "Wallet: " + JSON.stringify(e);
    } catch (_) {
      return "Unknown wallet error";
    }
  }

  function availableWallets() {
    const cardano = window.cardano || {};
    return Object.keys(cardano)
      .filter((key) => {
        const w = cardano[key];
        return w && typeof w === "object" && typeof w.enable === "function" && w.name;
      })
      .map((key) => ({ id: key, name: cardano[key].name, icon: cardano[key].icon || "" }));
  }

  function attach(app) {
    const ports = app.ports;
    let api = null;

    function send(port, value) {
      if (ports[port]) ports[port].send(value);
    }

    async function connect(id, silent) {
      try {
        const wallet = window.cardano && window.cardano[id];
        if (!wallet) throw new Error("Wallet " + id + " is not installed");
        api = await wallet.enable();
        const networkId = await api.getNetworkId();
        const address = await api.getChangeAddress(); // hex-encoded address bytes
        localStorage.setItem(STORAGE_KEY, id);
        send("walletConnected", { id: id, name: wallet.name, networkId: networkId, address: address });
      } catch (e) {
        api = null;
        if (!silent) send("walletError", errorMessage(e));
      }
    }

    if (ports.detectWallets) {
      ports.detectWallets.subscribe(() => {
        // Wallet extensions inject window.cardano asynchronously, so wait a moment
        setTimeout(async () => {
          const wallets = availableWallets();
          send("walletsDetected", wallets);
          const saved = localStorage.getItem(STORAGE_KEY);
          const w = saved && window.cardano && window.cardano[saved];
          if (w && typeof w.isEnabled === "function") {
            try {
              if (await w.isEnabled()) connect(saved, true);
            } catch (_) {
              /* ignore */
            }
          }
        }, 500);
      });
    }

    if (ports.connectWallet) {
      ports.connectWallet.subscribe((id) => connect(id, false));
    }

    if (ports.disconnectWallet) {
      ports.disconnectWallet.subscribe(() => {
        api = null;
        localStorage.removeItem(STORAGE_KEY);
      });
    }

    if (ports.signTx) {
      ports.signTx.subscribe(async (txHex) => {
        if (!api) {
          send("walletError", "No wallet connected");
          return;
        }
        try {
          // partialSign = true: the wallet adds the witnesses it can and returns only the witness set
          const witnessHex = await api.signTx(txHex, true);
          send("txSigned", witnessHex);
        } catch (e) {
          send("walletError", errorMessage(e));
        }
      });
    }
  }

  window.VaultWallet = { attach: attach };
})();

