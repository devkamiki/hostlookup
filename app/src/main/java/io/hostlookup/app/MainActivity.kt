package io.hostlookup.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DnsBridge.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { HostLookupTheme { HostLookupApp() } }
    }
}

private enum class Screen { HOME, LOADING, RESULTS, DETAIL, WHOIS, ERROR }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HostLookupTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(24.dp),
        large = RoundedCornerShape(30.dp),
        extraLarge = RoundedCornerShape(40.dp),
    )
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Composable
private fun HostLookupApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    var selectedResolver by rememberSaveable { mutableStateOf(OVERALL) }
    var detailType by rememberSaveable { mutableStateOf("") }
    var activeDomain by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf("") }
    val resultsListState = rememberLazyListState()

    fun startLookup(input: String) {
        val domain = try {
            normalizeDomain(input)
        } catch (error: IllegalArgumentException) {
            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            return
        }
        activeDomain = domain
        scope.launch { resultsListState.scrollToItem(0) }
        screen = Screen.LOADING
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val parsed = LookupResult.parse(DnsBridge.lookup(domain))
                    val enrichment = RipeStatClient.complete(parsed.ipAddresses(), parsed.whoisEntries)
                    parsed.replaceWhois(enrichment.entries, enrichment.error)
                    parsed
                }
            }.onSuccess {
                result = it
                selectedResolver = OVERALL
                rememberDomain(context, domain)
                screen = Screen.RESULTS
            }.onFailure {
                errorMessage = it.message ?: it.toString()
                screen = Screen.ERROR
            }
        }
    }

    fun navigateBack() {
        screen = when (screen) {
            Screen.DETAIL, Screen.WHOIS -> Screen.RESULTS
            Screen.RESULTS, Screen.ERROR -> Screen.HOME
            Screen.LOADING -> Screen.HOME
            Screen.HOME -> Screen.HOME
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { navigateBack() }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { destination ->
        when (destination) {
            Screen.HOME -> HomeScreen(onLookup = ::startLookup)
            Screen.LOADING -> LoadingScreen(activeDomain)
            Screen.RESULTS -> result?.let { lookup ->
                ResultsScreen(
                    result = lookup,
                    listState = resultsListState,
                    selectedResolver = selectedResolver,
                    onResolverSelected = { selectedResolver = it },
                    onLookup = ::startLookup,
                    onBack = { screen = Screen.HOME },
                    onRecordType = { detailType = it; screen = Screen.DETAIL },
                    onWhois = { screen = Screen.WHOIS },
                )
            }
            Screen.DETAIL -> result?.let {
                DetailScreen(it, selectedResolver, detailType) { screen = Screen.RESULTS }
            }
            Screen.WHOIS -> result?.let { WhoisScreen(it) { screen = Screen.RESULTS } }
            Screen.ERROR -> ErrorScreen(
                domain = activeDomain,
                message = errorMessage,
                onRetry = { startLookup(activeDomain) },
                onBack = { screen = Screen.HOME },
            )
        }
    }
}

@Composable
private fun HomeScreen(onLookup: (String) -> Unit) {
    val context = LocalContext.current
    var domain by rememberSaveable { mutableStateOf("") }
    val recent = remember { recentDomains(context) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.size(88.dp),
                shape = RoundedCornerShape(topStart = 44.dp, topEnd = 20.dp, bottomEnd = 44.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("HostLookup", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                Text(
                    "DNS answers, compared clearly",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Domain name") },
                        placeholder = { Text("example.com") },
                        leadingIcon = { Icon(Icons.Default.Public, null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onLookup(domain) }),
                    )
                    Button(
                        onClick = { onLookup(domain) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 10.dp, bottomStart = 28.dp),
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Find DNS records", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(18.dp))
                    Text("mhost 0.11.3 · 25 record types · RIPEstat", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent lookups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.forEach { item -> AssistChip(onClick = { onLookup(item) }, label = { Text(item) }) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingScreen(domain: String) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                LoadingIndicator(Modifier.padding(24.dp).size(72.dp))
            }
            Text("Looking up $domain", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Comparing every available provider\nacross 25 DNS record types",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsScreen(
    result: LookupResult,
    listState: LazyListState,
    selectedResolver: String,
    onResolverSelected: (String) -> Unit,
    onLookup: (String) -> Unit,
    onBack: () -> Unit,
    onRecordType: (String) -> Unit,
    onWhois: () -> Unit,
) {
    var domain by rememberSaveable(result.domain) { mutableStateOf(result.domain) }
    val records = remember(result, selectedResolver) { result.recordsFor(selectedResolver) }
    val grouped = remember(records) { records.groupBy { it.type } }
    Scaffold(
        topBar = { HostTopBar(result.domain, onBack) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onLookup(domain) }),
                    )
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(onClick = { onLookup(domain) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Search, "Lookup")
                    }
                }
            }
            item {
                SummaryCard(
                    label = if (selectedResolver == OVERALL) "Overall result" else selectedResolver,
                    recordCount = records.size,
                    providerCount = result.responders.size,
                    elapsedMs = result.elapsedMs,
                )
            }
            item {
                ResolverPicker(result, selectedResolver, onResolverSelected)
            }
            if (grouped.isEmpty()) {
                item { EmptyCard() }
            } else {
                TYPE_ORDER.filter { grouped.containsKey(it) }.forEach { type ->
                    item(key = type) { RecordCard(type, grouped.getValue(type), selectedResolver == OVERALL) { onRecordType(type) } }
                }
            }
            if (selectedResolver == OVERALL && (result.whoisEntries.isNotEmpty() || result.whoisError.isNotEmpty())) {
                item { WhoisCallout(result, onWhois) }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, recordCount: Int, providerCount: Int, elapsedMs: Long) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 14.dp, bottomEnd = 34.dp, bottomStart = 34.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("$recordCount records", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
            Text("$providerCount providers · ${formatDuration(elapsedMs)}", color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolverPicker(result: LookupResult, selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(result) { listOf(OVERALL) + result.availableResolvers() }
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("RESULT SOURCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (selected == OVERALL) "Overall · all providers" else "$selected · ${result.recordCountFor(selected)} records",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                shape = MaterialTheme.shapes.medium,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option == OVERALL) "Overall · all providers" else "$option · ${result.recordCountFor(option)} records") },
                        onClick = { expanded = false; onSelected(option) },
                    )
                }
            }
        }
        Text(
            "Only providers with answers are shown; duplicate IPv4 and IPv6 endpoints are combined.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordCard(type: String, records: List<DisplayRecord>, overall: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 8.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(type, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Text("${records.size} ${if (records.size == 1) "answer" else "answers"}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("›", style = MaterialTheme.typography.headlineSmall)
            }
            records.take(2).forEach { record ->
                SelectionContainer {
                    Text(record.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    buildString {
                        append("TTL ${record.ttl}s")
                        if (overall) append(" · ${record.resolvers.size} provider${if (record.resolvers.size == 1) "" else "s"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(records.size > 2) {
                Text("+ ${records.size - 2} more", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(22.dp)) {
            Text("No records from this provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose Overall or another provider.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WhoisCallout(result: LookupResult, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).then(if (result.whoisEntries.isNotEmpty()) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 30.dp, bottomEnd = 30.dp, bottomStart = 30.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Public, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("WHOIS & RIPESTAT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text(
                    if (result.whoisEntries.isNotEmpty()) "${result.whoisResourceCount()} IP addresses enriched" else "RIPEstat unavailable",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (result.whoisEntries.isNotEmpty()) "Ownership, network and location details" else result.whoisError,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (result.whoisEntries.isNotEmpty()) Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(result: LookupResult, resolver: String, type: String, onBack: () -> Unit) {
    val records = remember(result, resolver, type) { result.recordsFor(resolver).filter { it.type == type } }
    Scaffold(topBar = { HostTopBar("$type records", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 4.dp)) {
                    Text(result.domain, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(typeDescription(type), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(records) { record -> DetailRecordCard(record) }
        }
    }
}

@Composable
private fun DetailRecordCard(record: DisplayRecord) {
    ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionContainer { Text(record.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge) }
            MetaRow("Name", record.name)
            MetaRow("TTL", "${record.ttl} seconds")
            MetaRow("Response", "${record.responseMs} ms")
            MetaRow("Provider", record.resolvers.joinToString())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhoisScreen(result: LookupResult, onBack: () -> Unit) {
    Scaffold(topBar = { HostTopBar("WHOIS & network", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    "RIPEstat network information for unique A and AAAA addresses.",
                    modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.whoisError.isNotBlank()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(result.whoisError, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            items(result.whoisEntries) { entry ->
                val kind = firstKey(entry)
                ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(kind.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(whoisSummary(entry), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(82.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    )
}

@Composable
private fun ErrorScreen(domain: String, message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Lookup failed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(domain, fontWeight = FontWeight.Bold)
                Text(message)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("New lookup") }
            }
        }
    }
}

private const val OVERALL = "Overall"
private val TYPE_ORDER = listOf(
    "NS", "A", "AAAA", "CNAME", "MX", "TXT", "SOA", "CAA", "HTTPS", "SVCB", "SRV",
    "TLSA", "SSHFP", "DNSKEY", "DS", "RRSIG", "NSEC", "NSEC3", "NSEC3PARAM", "NAPTR",
    "PTR", "HINFO", "ANAME", "OPENPGPKEY", "NULL",
)

private data class RawRecord(
    val type: String,
    val name: String,
    val ttl: Long,
    val value: String,
    val resolver: String,
    val responseMs: Long,
)

private data class DisplayRecord(
    val type: String,
    val name: String,
    var ttl: Long,
    val value: String,
    var responseMs: Long,
    val resolvers: LinkedHashSet<String> = linkedSetOf(),
)

private class LookupResult(
    val domain: String,
    val elapsedMs: Long,
    private val records: List<RawRecord>,
    private val resolvers: Set<String>,
    val responders: Set<String>,
    val whoisEntries: MutableList<JSONObject>,
    var whoisError: String,
) {
    fun recordsFor(selected: String): List<DisplayRecord> {
        val unique = linkedMapOf<String, DisplayRecord>()
        records.forEach { raw ->
            if (selected != OVERALL && selected != raw.resolver) return@forEach
            val key = "${raw.type}\u0000${raw.name}\u0000${raw.value}"
            val current = unique[key]
            if (current == null) {
                unique[key] = DisplayRecord(raw.type, raw.name, raw.ttl, raw.value, raw.responseMs, linkedSetOf(raw.resolver))
            } else {
                current.ttl = minOf(current.ttl, raw.ttl)
                current.responseMs = minOf(current.responseMs, raw.responseMs)
                current.resolvers += raw.resolver
            }
        }
        return unique.values.sortedWith(compareBy<DisplayRecord> { it.type }.thenBy { it.value })
    }

    fun availableResolvers(): List<String> = resolvers.sortedWith(compareByDescending<String> { recordCountFor(it) }.thenBy { it.lowercase() })
    fun recordCountFor(resolver: String): Int = recordsFor(resolver).size
    fun ipAddresses(): Set<String> = records.filter { it.type == "A" || it.type == "AAAA" }.map { it.value }.filter { it.matches(Regex("[0-9a-fA-F:.]+")) }.toCollection(linkedSetOf())
    fun replaceWhois(entries: List<JSONObject>, error: String?) { whoisEntries.clear(); whoisEntries.addAll(entries); whoisError = error.orEmpty() }
    fun whoisResourceCount(): Int = whoisEntries.mapNotNull { entry -> entry.optJSONObject(firstKey(entry))?.optString("resource")?.takeIf(String::isNotBlank) }.toSet().size

    companion object {
        fun parse(raw: String): LookupResult {
            val root = JSONObject(raw)
            if (root.has("error")) error(root.optString("error"))
            val records = mutableListOf<RawRecord>()
            val resolvers = linkedSetOf<String>()
            val responders = linkedSetOf<String>()
            val lookups = root.getJSONObject("lookups").getJSONArray("lookups")
            for (i in 0 until lookups.length()) {
                val lookup = lookups.getJSONObject(i)
                val resolver = resolverName(lookup.optString("name_server", "Unknown"))
                val response = lookup.optJSONObject("result")?.optJSONObject("Response") ?: continue
                responders += resolver
                val responseMs = durationMs(response)
                val answerRecords = response.optJSONArray("records") ?: continue
                for (j in 0 until answerRecords.length()) {
                    val record = answerRecords.getJSONObject(j)
                    val data = record.optJSONObject("data") ?: continue
                    records += RawRecord(
                        record.optString("type", "UNKNOWN"),
                        record.optString("name", root.optString("domain")),
                        record.optLong("ttl"),
                        recordValue(data),
                        resolver,
                        responseMs,
                    )
                    resolvers += resolver
                }
            }
            val whois = mutableListOf<JSONObject>()
            val entries = root.optJSONObject("whois")?.optJSONArray("whois")
            if (entries != null) for (i in 0 until entries.length()) entries.optJSONObject(i)?.let(whois::add)
            return LookupResult(root.getString("domain"), root.optLong("elapsed_ms"), records, resolvers, responders, whois, root.optString("whois_error"))
        }
    }
}

private fun resolverName(raw: String): String {
    val label = if ("name=" in raw) raw.substringAfter("name=").replace("\"", "").trim()
    else raw.replace(Regex("^[a-z]+:"), "").replace(Regex(":53$"), "")
    return label.replace(Regex("\\s+[12]$"), "")
}

private fun durationMs(response: JSONObject): Long {
    val duration = response.optJSONObject("response_time") ?: return 0
    return duration.optLong("secs") * 1000 + duration.optLong("nanos") / 1_000_000
}

private fun recordValue(data: JSONObject): String {
    data.optJSONObject("TXT")?.optJSONArray("txt_data")?.let { chunks ->
        val result = StringBuilder()
        for (i in 0 until chunks.length()) {
            val bytes = chunks.optJSONArray(i) ?: continue
            val raw = ByteArray(bytes.length()) { bytes.optInt(it).toByte() }
            result.append(String(raw, StandardCharsets.UTF_8))
        }
        return result.toString()
    }
    val key = firstKey(data)
    val value = data.opt(key)
    return if (data.length() == 1 && value != null) humanJson(value) else data.toString(2)
}

private fun humanJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().joinToString(" · ") { "${it.replace('_', ' ')}: ${humanJson(value.opt(it))}" }
    is JSONArray -> (0 until value.length()).joinToString(", ") { humanJson(value.opt(it)) }
    else -> value?.toString().orEmpty()
}

private fun firstKey(value: JSONObject): String = value.keys().asSequence().firstOrNull() ?: "result"

private fun whoisSummary(entry: JSONObject): String {
    val kind = firstKey(entry)
    val payload = entry.optJSONObject(kind) ?: return humanJson(entry)
    val lines = mutableListOf<String>()
    fun add(label: String, value: String?) { if (!value.isNullOrBlank() && value != "null" && value != "[]") lines += "$label  ·  $value" }
    add("Address", payload.optString("resource"))
    when (kind) {
        "NetworkInfo" -> payload.optJSONObject("network_info")?.let { add("Prefix", it.optString("prefix")); add("ASN", humanJson(it.optJSONArray("asns"))) }
        "Whois" -> payload.optJSONObject("whois")?.let {
            add("Organization", it.optString("organization")); add("Network", it.optString("cidr")); add("Name", it.optString("net_name")); add("Country", it.optString("country")); add("Registry", it.optString("source"))
        }
        "GeoLocation" -> payload.optJSONObject("geo_location")?.optJSONArray("located_resources")?.optJSONObject(0)?.optJSONArray("locations")?.optJSONObject(0)?.let {
            add("City", it.optString("city")); add("Country", it.optString("country")); add("Range", humanJson(it.optJSONArray("resources")))
        }
        "Error" -> add("Error", humanJson(payload))
    }
    return lines.ifEmpty { listOf(humanJson(payload)) }.joinToString("\n")
}

private fun normalizeDomain(input: String): String {
    var domain = input.trim().lowercase(Locale.ROOT).replaceFirst(Regex("^[a-z][a-z0-9+.-]*://"), "")
    domain = domain.substringBefore('/').substringBeforeLast(':', domain)
    domain = domain.trimEnd('.')
    require(domain.isNotBlank()) { "Enter a domain name" }
    domain = try { IDN.toASCII(domain) } catch (_: Exception) { throw IllegalArgumentException("That domain name is not valid") }
    require(domain.length <= 253 && domain.matches(Regex("(?i)(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) { "That domain name is not valid" }
    return domain
}

private fun rememberDomain(context: Context, domain: String) {
    val prefs = context.getSharedPreferences("hostlookup", Context.MODE_PRIVATE)
    val values = linkedSetOf(domain).apply { addAll(prefs.getStringSet("recent", emptySet()).orEmpty()) }.take(5).toSet()
    prefs.edit().putStringSet("recent", values).apply()
}

private fun recentDomains(context: Context): List<String> = context.getSharedPreferences("hostlookup", Context.MODE_PRIVATE).getStringSet("recent", emptySet()).orEmpty().take(5)

private fun typeDescription(type: String): String = mapOf(
    "NS" to "Authoritative name servers for this domain.",
    "A" to "IPv4 addresses used to reach this host.",
    "AAAA" to "IPv6 addresses used to reach this host.",
    "CNAME" to "Canonical aliases pointing to another host.",
    "MX" to "Mail servers receiving email for this domain.",
    "TXT" to "Text data used for ownership and email-policy verification.",
    "SOA" to "Administrative and timing information for the DNS zone.",
    "CAA" to "Certificate authorities permitted to issue certificates.",
    "DNSKEY" to "Public keys used to validate DNSSEC signatures.",
    "DS" to "Delegation signer records linking the DNSSEC chain.",
)[type] ?: "DNS $type responses returned by the selected provider."

private fun formatDuration(ms: Long): String = if (ms >= 1000) String.format(Locale.ROOT, "%.1f s", ms / 1000.0) else "$ms ms"
