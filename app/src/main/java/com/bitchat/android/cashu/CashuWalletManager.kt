package com.bitchat.android.cashu

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cashudevkit.*
import java.io.File

class CashuWalletManager private constructor(private val appContext: Context) {

    companion object {
        private const val PREFS = "cashu_prefs"
        private const val KEY_MNEMONIC = "mnemonic"
        private const val KEY_DEFAULT_MINT = "default_mint_url"
        private const val DB_FILE = "cashu_wallet.db"
        private const val TAG = "CashuWalletMgr"

        @Volatile private var instance: CashuWalletManager? = null

        fun getInstance(context: Context): CashuWalletManager {
            return instance ?: synchronized(this) {
                instance ?: CashuWalletManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val walletMutex = Mutex()
    private val wallets = mutableMapOf<String, Wallet>() // mintUrl -> Wallet

    // Shared DB instance for all wallets
    @Volatile private var db: WalletSqliteDatabase? = null

    private suspend fun getOrCreateMnemonic(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_MNEMONIC, null)?.let { return@withContext it }
        val mnemonic = generateMnemonic()
        prefs.edit().putString(KEY_MNEMONIC, mnemonic).apply()
        mnemonic
    }

    private suspend fun getDb(): WalletSqliteDatabase = withContext(Dispatchers.IO) {
        db?.let { return@withContext it }
        val dbFile: File = appContext.getDatabasePath(DB_FILE).apply {
            parentFile?.let { if (!it.exists()) it.mkdirs() }
        }
        val sqlite = WalletSqliteDatabase(
            dbFile.toString()
        )
        db = sqlite
        sqlite
    }

    suspend fun getOrCreateWallet(
        mintUrl: String,
        unit: CurrencyUnit = CurrencyUnit.Sat,
        targetProofCount: UInt? = null,
    ): Wallet = walletMutex.withLock {
        wallets[mintUrl]?.let { return it }

        val mnemonic = getOrCreateMnemonic()
        val sqlite = getDb()
        val wallet = Wallet(
            mintUrl = mintUrl,
            unit = unit,
            mnemonic = mnemonic,
            db = sqlite,
            config = WalletConfig(targetProofCount = targetProofCount)
        )

        try {
            wallet.getMintInfo()
            wallet.refreshKeysets()
        } catch (_: Exception) { }

        wallets[mintUrl] = wallet
        wallet
    }

    fun getDefaultMintUrl(): String? = prefs.getString(KEY_DEFAULT_MINT, null)

    fun setDefaultMintUrl(url: String) {
        prefs.edit().putString(KEY_DEFAULT_MINT, url).apply()
    }

    suspend fun getDefaultBalance(): ULong {
        val mint = getDefaultMintUrl() ?: return 0u
        val wallet = getOrCreateWallet(mint)
        return wallet.totalBalance().value
    }

    suspend fun send(
        amountSat: ULong,
        memo: String? = null,
        sendKind: SendKind = SendKind.OnlineExact,
        includeFee: Boolean = true,
        lockToPubkey: String? = null,
        maxProofs: UInt? = null
    ): String {
        val mint = getDefaultMintUrl()
            ?: throw IllegalStateException("Default mint not set")

        val conditions = lockToPubkey?.let {
            // Ensure compressed SEC1 format (33 bytes hex) per NUT-11; if x-only, prefix 02
            val norm = try {
                val hex = it.lowercase()
                if (hex.length == 64) "02$hex" else hex
            } catch (_: Exception) { it }
            Log.d(TAG, "send(): lockToPubkey provided len=${it.length}, normalized len=${norm.length}")
            SpendingConditions.P2pk(norm, null)
        }
        val wallet = getOrCreateWallet(mint, CurrencyUnit.Sat)
        val options = SendOptions(
            memo = null,
            conditions = conditions,
            amountSplitTarget = SplitTarget.None,
            sendKind = sendKind,
            includeFee = includeFee,
            maxProofs = maxProofs,
            metadata = emptyMap()
        )

        val prepared = wallet.prepareSend(Amount(amountSat), options)
        val token = prepared.confirm(memo)
        return token.encode()
    }

    suspend fun receive(tokenString: String, nostrPrivkey: String? = null): ULong {
        val token = Token.decode(tokenString)
        val mintUrl = token.mintUrl().url
        val unit = token.unit() ?: CurrencyUnit.Sat

        val signingKeys = nostrPrivkey?.let { listOf(SecretKey(nostrPrivkey)) } ?: emptyList()

        val wallet = getOrCreateWallet(mintUrl, unit)
        val received = wallet.receive(
            token = token,
            options = ReceiveOptions(
                amountSplitTarget = SplitTarget.None,
                p2pkSigningKeys = signingKeys,
                preimages = emptyList(),
                metadata = emptyMap()
            )
        )
        return received.value
    }

    // Expose mnemonic for UI (read-only). Use carefully.
    suspend fun peekMnemonic(): String = withContext(Dispatchers.IO) {
        getOrCreateMnemonic()
    }

    suspend fun listMints(): Map<MintUrl, MintInfo?> {
        val sqlite = getDb()
        return sqlite.getMints()
    }

    suspend fun getBalance(mintUrl: String): ULong {
        val wallet = getOrCreateWallet(mintUrl)
        return wallet.totalBalance().value
    }

    suspend fun getAllBalances(): Map<String, ULong> {
        val sqlite = getDb()
        val mints = sqlite.getMints().keys.map { it.url }
        val results = mutableMapOf<String, ULong>()
        for (mint in mints) {
            try {
                results[mint] = getBalance(mint)
            } catch (_: Exception) { }
        }
        return results
    }
}
