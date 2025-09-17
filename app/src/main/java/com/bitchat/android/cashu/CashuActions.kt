// Minimal helpers to drive wallet from ChatViewModel
package com.bitchat.android.cashu

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.Token
import org.cashudevkit.CurrencyUnit

object CashuActions {
    fun setDefaultMint(context: Context, url: String, scope: CoroutineScope, onResult: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val mgr = CashuWalletManager.getInstance(context)
                mgr.setDefaultMintUrl(url)
                // Eagerly init wallet and fetch mint info
                mgr.getOrCreateWallet(url)
                withContext(Dispatchers.Main) { onResult(true, "Default mint set to $url") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "Unknown error") }
            }
        }
    }

    fun currentBalance(context: Context, scope: CoroutineScope, onResult: (ULong?, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val mgr = CashuWalletManager.getInstance(context)
                val bal = mgr.getDefaultBalance()
                withContext(Dispatchers.Main) { onResult(bal, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, e.message) }
            }
        }
    }

    fun sendToken(
        context: Context,
        sats: ULong,
        memo: String?,
        scope: CoroutineScope,
        lockToPubkey: String? = null,
        onResult: (String?, String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val mgr = CashuWalletManager.getInstance(context)
                val token = mgr.send(amountSat = sats, memo = memo, lockToPubkey = lockToPubkey)
                withContext(Dispatchers.Main) { onResult(token, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, e.message) }
            }
        }
    }

    fun receiveToken(
        context: Context,
        token: String,
        scope: CoroutineScope,
        nostrPrivkeyHex: String? = null,
        onResult: (ULong?, String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // Enforce only SAT tokens
                val decoded = Token.decode(token)
                val unit = decoded.unit() ?: CurrencyUnit.Sat
                if (unit != CurrencyUnit.Sat) {
                    withContext(Dispatchers.Main) { onResult(null, "unsupported unit: ${unit}. only 'sat' is supported") }
                    return@launch
                }

                val mgr = CashuWalletManager.getInstance(context)
                val credited = mgr.receive(token, nostrPrivkeyHex)
                withContext(Dispatchers.Main) { onResult(credited, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, e.message) }
            }
        }
    }
}
