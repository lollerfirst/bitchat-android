package com.bitchat.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.bitchat.android.cashu.CashuWalletManager
import org.cashudevkit.Token
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.Amount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashuWalletPanel(viewModel: ChatViewModel, onClose: () -> Unit, prefillToken: String? = null) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val context = LocalContext.current

    // Colors / styles
    val green = Color(0xFF00C851) // bitchat-green
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // State
    var allMints by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedMint by remember { mutableStateOf<String?>(null) }
    var setMintStatus by remember { mutableStateOf<String?>(null) }

    // Live per-mint balances
    var balances by remember { mutableStateOf<Map<String, ULong>>(emptyMap()) }

    // Helper to refresh balances for all mints
    suspend fun refreshAllBalances() {
        val mgr = CashuWalletManager.getInstance(context)
        val newBalances = withContext(Dispatchers.IO) { mgr.getAllBalances() }
        balances = newBalances
    }

    var sendAmount by remember { mutableStateOf("") }
    var sendMemo by remember { mutableStateOf("") }
    var sendToken by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }

    // P2PK lock context
    val pendingLockLabel by viewModel.pendingCashuLockLabel.observeAsState()

    // Two-step send confirmation
    var showSendConfirm by remember { mutableStateOf(false) }
    var preparedAmount by remember { mutableStateOf<ULong?>(null) }
    var preparedMemo by remember { mutableStateOf<String?>(null) }

    var receiveTokenText by remember { mutableStateOf(prefillToken ?: "") }
    var receivedAmount by remember { mutableStateOf<ULong?>(null) }
    var receiveError by remember { mutableStateOf<String?>(null) }
    var isReceiving by remember { mutableStateOf(false) }

    // Seed phrase UI state
    var showSeed by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf<String?>(null) }

    // Token decode preview state
    var decodedMint by remember { mutableStateOf<String?>(null) }
    var decodedUnit by remember { mutableStateOf<CurrencyUnit?>(null) }
    var decodedAmount by remember { mutableStateOf<ULong?>(null) }
    var decodedMemo by remember { mutableStateOf<String?>(null) }
    var tokenDecodeError by remember { mutableStateOf<String?>(null) }

    fun decodeTokenPreview(raw: String) {
        decodedMint = null
        decodedUnit = null
        decodedAmount = null
        tokenDecodeError = null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        try {
            val token = Token.decode(trimmed)
            decodedMint = token.mintUrl().url
            decodedUnit = token.unit() ?: CurrencyUnit.Sat
            val proofs = token.proofsSimple()
            var decAmount = 0uL
            for (proof in proofs) {
                decAmount += proof.amount().value
            }
            decodedAmount = decAmount
            decodedMemo = token.memo()
        } catch (_: Exception) {
            tokenDecodeError = "invalid token"
        }
    }

    // Load mints and default on open
    LaunchedEffect(Unit) {
        val mgr = CashuWalletManager.getInstance(context)
        val mintsMap = withContext(Dispatchers.IO) { mgr.listMints() }
        val mints = mintsMap.keys.map { it.url }.distinct()
        allMints = mints
        val def = mgr.getDefaultMintUrl()
        selectedMint = def ?: mints.firstOrNull()
        if (def == null && selectedMint != null) {
            withContext(Dispatchers.IO) { mgr.setDefaultMintUrl(selectedMint!!) }
            setMintStatus = "default mint set"
        }
    }

    // While seed is visible, set FLAG_SECURE to prevent screenshots
    LaunchedEffect(showSeed) {
        val activity = (context as? Activity) ?: run {
            var ctx: Context = context
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            ctx as? Activity
        }
        activity?.window?.let { win ->
            if (showSeed) {
                win.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // Auto-clear receive status after a short delay
    LaunchedEffect(receivedAmount, receiveError) {
        if (receivedAmount != null || receiveError != null) {
            delay(4000)
            receivedAmount = null
            receiveError = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("Cashu Wallet", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))

        // Mint selector as dropdown
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (allMints.isNotEmpty()) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedMint ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = allMints.isNotEmpty(),
                placeholder = { Text("No mints yet — paste an cashu token below", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                allMints.forEach { mint ->
                    DropdownMenuItem(
                        text = { Text(mint, style = textStyle) },
                        onClick = {
                            selectedMint = mint
                            expanded = false
                            viewModel.setDefaultCashuMint(mint) { _, msg -> setMintStatus = msg }
                        }
                    )
                }
            }
        }

        // Result of receive operation (below mint combobox, before balance)
        receivedAmount?.let {
            Text(
                text = "RECEIVED ${it} sats",
                color = green,
                style = textStyle.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        receiveError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = textStyle.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Initial and on-change balance refresh
        LaunchedEffect(allMints) {
            if (allMints.isNotEmpty()) refreshAllBalances()
        }
        LaunchedEffect(selectedMint) {
            if (selectedMint != null && allMints.isNotEmpty()) refreshAllBalances()
        }

        // Balance
        if (selectedMint != null) {
            val bal = balances[selectedMint] ?: 0u
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = bal,
                    transitionSpec = {
                        val duration = 350
                        if (targetState > initialState) {
                            (slideInVertically(animationSpec = tween(duration)) { it } + fadeIn(tween(duration))) togetherWith
                            (slideOutVertically(animationSpec = tween(duration)) { -it } + fadeOut(tween(duration)))
                        } else {
                            (slideInVertically(animationSpec = tween(duration)) { -it } + fadeIn(tween(duration))) togetherWith
                            (slideOutVertically(animationSpec = tween(duration)) { it } + fadeOut(tween(duration)))
                        }
                    },
                    label = "balance-anim",
                    contentKey = { it },
                ) { newBal ->
                    Text(
                        text = "$newBal sats",
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Send
        Text("Send bitcoin", style = MaterialTheme.typography.titleSmall)
        if (!pendingLockLabel.isNullOrBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Locked (P2PK)",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "locking to ${pendingLockLabel}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                style = textStyle
            )
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sendAmount,
                onValueChange = { sendAmount = it.filter { ch -> ch.isDigit() } },
                label = { Text("Amount (sats)") },
                singleLine = true,
                enabled = allMints.isNotEmpty(),
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = sendMemo,
                onValueChange = { sendMemo = it },
                label = { Text("Memo (optional)") },
                singleLine = true,
                enabled = allMints.isNotEmpty(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "prepare",
                color = if (allMints.isNotEmpty()) green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                style = textStyle,
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .let { mod -> if (allMints.isNotEmpty()) mod.clickable {
                        val amt = sendAmount.toULongOrNull()
                        if (amt == null || amt == 0uL) {
                            sendError = "Invalid amount"
                            showSendConfirm = false
                        } else {
                            sendError = null
                            preparedAmount = amt
                            preparedMemo = if (sendMemo.isBlank()) null else sendMemo
                            showSendConfirm = true
                        }
                    } else mod }
            )
        }
        if (showSendConfirm && preparedAmount != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Send ${preparedAmount} sats" + (preparedMemo?.let { " — \"$it\"" } ?: ""), style = textStyle)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        if (!isSending) {
                            Text(
                                text = "confirm",
                                color = green,
                                style = textStyle,
                                modifier = Modifier
                                    .padding(vertical = 8.dp, horizontal = 8.dp)
                                    .clickable {
                                        isSending = true
                                        viewModel.sendCashuToken(preparedAmount!!, preparedMemo) { token, err ->
                                            sendToken = token
                                            sendError = err
                                            isSending = false
                                            showSendConfirm = false
                                            token?.let {
                                                viewModel.sendMessage(it)
                                                onClose()
                                            }
                                        }
                                    }
                            )
                            Text(
                                text = "cancel",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                style = textStyle,
                                modifier = Modifier
                                    .padding(vertical = 8.dp, horizontal = 8.dp)
                                    .clickable {
                                        showSendConfirm = false
                                        preparedAmount = null
                                        preparedMemo = null
                                    }
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = green
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("sending…", style = textStyle, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        sendToken?.let { Text("Token: $it", style = textStyle) }
        sendError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = textStyle) }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Receive
        Text("Receive bitcoin", style = MaterialTheme.typography.titleSmall)

        // Decode preview reacts to token changes
        LaunchedEffect(receiveTokenText) { decodeTokenPreview(receiveTokenText) }

        if (decodedMint != null && tokenDecodeError == null) {
            // Show decoded token info instead of textarea
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text("mint: $decodedMint", style = textStyle)
                val unitText = when (decodedUnit ?: CurrencyUnit.Sat) {
                    CurrencyUnit.Sat -> "sat"
                    CurrencyUnit.Msat -> "msat"
                    else -> (decodedUnit?.toString()?.lowercase() ?: "sat")
                }
                decodedAmount?.let { amt ->
                    Text("amount: $amt $unitText", style = textStyle)
                }
                if ((decodedUnit ?: CurrencyUnit.Sat) != CurrencyUnit.Sat) {
                    Text(
                        text = "unsupported unit: $unitText — only 'sat' is supported",
                        color = MaterialTheme.colorScheme.error,
                        style = textStyle
                    )
                }
                decodedMemo?.let { memo ->
                    Text("memo: $memo", style = textStyle)
                }
            }
        } else {
            // Fallback to textarea (no token or invalid)
            OutlinedTextField(
                value = receiveTokenText,
                onValueChange = { receiveTokenText = it },
                label = { Text("Paste cashu token") },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 120.dp)
            )
            tokenDecodeError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = textStyle
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val hasTokenInput = receiveTokenText.isNotBlank()
            if (!hasTokenInput) {
                Text(
                    text = "paste",
                    color = green,
                    style = textStyle,
                    modifier = Modifier
                        .padding(vertical = 8.dp, horizontal = 8.dp)
                        .clickable {
                            val clip = clipboard.getText()
                            val pasted = clip?.text ?: ""
                            if (pasted.isNotBlank()) {
                                receiveTokenText = pasted
                            }
                        }
                )
            } else {
                Text(
                    text = "clear",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = textStyle,
                    modifier = Modifier
                        .padding(vertical = 8.dp, horizontal = 8.dp)
                        .clickable {
                            receiveTokenText = ""
                        }
                )
            }
            Spacer(Modifier.width(8.dp))
            // Receive button + spinner
            val isDecodedOk = (decodedMint != null && tokenDecodeError == null)
            val isUnsupportedUnit = (decodedUnit ?: CurrencyUnit.Sat) != CurrencyUnit.Sat && isDecodedOk
            if (!isReceiving) {
                val receiveEnabled = isDecodedOk && !isUnsupportedUnit
                Text(
                    text = if (isUnsupportedUnit) "receive (disabled)" else "receive",
                    color = if (receiveEnabled) green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    style = textStyle,
                    modifier = Modifier
                        .padding(vertical = 8.dp, horizontal = 8.dp)
                        .let { mod -> if (receiveEnabled) mod.clickable {
                            isReceiving = true
                            viewModel.receiveCashuToken(receiveTokenText.trim()) { credited, err ->
                                receivedAmount = credited
                                receiveError = err
                                isReceiving = false
                            }
                        } else mod }
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = green
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("receiving…", style = textStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // After receive, refresh the mints list to include the mint from the token
        LaunchedEffect(receivedAmount) {
            if (receivedAmount != null) {
                val mgr = CashuWalletManager.getInstance(context)
                val mintsMap = withContext(Dispatchers.IO) { mgr.listMints() }
                val mints = mintsMap.keys.map { it.url }.distinct()
                allMints = mints
                if (selectedMint == null && mints.isNotEmpty()) {
                    selectedMint = mints.first()
                    withContext(Dispatchers.IO) { mgr.setDefaultMintUrl(selectedMint!!) }
                }
                // Refresh balances after receive
                refreshAllBalances()
            }
        }

        // Also refresh balances when a send token is created (send completes)
        LaunchedEffect(sendToken) {
            if (sendToken != null && allMints.isNotEmpty()) {
                refreshAllBalances()
            }
        }

        // Seed phrase section (at bottom)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Seed phrase", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        // Confirmation dialog to reveal seed
        var showSeedConfirmDialog by remember { mutableStateOf(false) }

        // Load the seed when the user first attempts to show it
        LaunchedEffect(showSeed) {
            if (showSeed && seed == null) {
                try {
                    seed = withContext(Dispatchers.IO) {
                        CashuWalletManager.getInstance(context).peekMnemonic()
                    }
                } catch (_: Exception) { }
            }
        }
        // Auto-hide seed after timeout when visible
        LaunchedEffect(showSeed) {
            if (showSeed) {
                // Hide after 20 seconds
                delay(20000)
                showSeed = false
            }
        }

        var justCopied by remember { mutableStateOf(false) }
        LaunchedEffect(justCopied) {
            if (justCopied) {
                delay(1500)
                justCopied = false
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val censored = remember(seed, showSeed) {
                if (!showSeed) {
                    val words = (seed?.split(" ")?.filter { it.isNotEmpty() }) ?: emptyList()
                    val count = if (words.isNotEmpty()) words.size else 12
                    (0 until count).joinToString(" ") { "****" }
                } else seed ?: ""
            }
            Text(
                text = censored.ifBlank { "******** ******** ******** ********" },
                style = textStyle,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            // Toggle show/hide
            Text(
                text = if (showSeed) "hide" else "show",
                color = green,
                style = textStyle,
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 8.dp)
                    .clickable {
                        if (showSeed) {
                            showSeed = false
                        } else {
                            showSeedConfirmDialog = true
                        }
                    }
            )
            // Copy button
            Text(
                text = if (justCopied) "copied" else "copy",
                color = if (showSeed && !seed.isNullOrBlank()) green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                style = textStyle,
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 8.dp)
                    .let { mod -> if (showSeed && !seed.isNullOrBlank()) mod.clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(seed!!))
                        justCopied = true
                    } else mod }
            )
        }

        if (showSeedConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showSeedConfirmDialog = false },
                title = { Text("Reveal seed phrase?") },
                text = { Text("Your seed phrase controls your funds. Anyone who sees it can take your money. Make sure no one is looking and avoid screenshots.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSeedConfirmDialog = false
                        showSeed = true
                    }) { Text("Reveal") }
                },
                dismissButton = {
                    TextButton(onClick = { showSeedConfirmDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashuWalletSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    prefillToken: String? = null
) {
    if (!isPresented) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Open at custom partially expanded height (~70%)
    LaunchedEffect(isPresented) {
        if (isPresented) sheetState.partialExpand()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = sheetState
    ) {
        // Make sheet content capable of expanding to full height and scroll if needed
        Box(Modifier.fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                CashuWalletPanel(viewModel, onClose = onDismiss, prefillToken = prefillToken)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
