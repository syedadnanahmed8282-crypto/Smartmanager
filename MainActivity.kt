package com.example

import android.app.Application
import android.app.DatePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// ১. ডাটা মডেল ও লোকাল ডেটাবেস সেকশন (Room Database Entities & DAOs)
// ============================================================================

@Entity(tableName = "daily_entries")
data class DailyEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val dateMillis: Long,          // নির্বাচিত তারিখের টাইমস্ট্যাম্প
    val quantity: Int,             // মালের পরিমাণ (পিস)
    val expense: Double,           // দৈনিক খরচ (টাকা)
    val note: String = "",         // মন্তব্য (ঐচ্ছিক)
    val folderName: String = "সাধারণ হিসাব" // হিসাবের ফোল্ডার বা বই
)

@Dao
interface DailyEntryDao {
    @Query("SELECT * FROM daily_entries ORDER BY dateMillis DESC, id DESC")
    fun getAllEntries(): Flow<List<DailyEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DailyEntry)

    @Update
    suspend fun updateEntry(entry: DailyEntry)

    @Delete
    suspend fun deleteEntry(entry: DailyEntry)

    @Query("DELETE FROM daily_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Int)

    @Query("DELETE FROM daily_entries")
    suspend fun clearAllEntries()
}

@Database(entities = [DailyEntry::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyEntryDao(): DailyEntryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "takahishab_production_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ============================================================================
// ২. রিপোজিটরি সেকশন (Data Repository Pattern)
// ============================================================================

class DailyEntryRepository(private val dailyEntryDao: DailyEntryDao) {
    val allEntries: Flow<List<DailyEntry>> = dailyEntryDao.getAllEntries()

    suspend fun insert(entry: DailyEntry) {
        dailyEntryDao.insertEntry(entry)
    }

    suspend fun update(entry: DailyEntry) {
        dailyEntryDao.updateEntry(entry)
    }

    suspend fun delete(entry: DailyEntry) {
        dailyEntryDao.deleteEntry(entry)
    }

    suspend fun deleteById(id: Int) {
        dailyEntryDao.deleteEntryById(id)
    }

    suspend fun clearAll() {
        dailyEntryDao.clearAllEntries()
    }
}

// ============================================================================
// ৩. ভিউমডেল সেকশন (ViewModel with StateFlow)
// ============================================================================

class DailyEntryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: DailyEntryRepository
    private val sharedPrefs = application.getSharedPreferences("takahishab_prefs", Context.MODE_PRIVATE)
    private val _dailyTarget = MutableStateFlow(sharedPrefs.getInt("daily_target", 800))
    val dailyTarget: StateFlow<Int> = _dailyTarget

    // থিম কন্ট্রোল স্টেট
    private val _currentTheme = MutableStateFlow(sharedPrefs.getString("app_theme", "DEFAULT") ?: "DEFAULT")
    val currentTheme: StateFlow<String> = _currentTheme

    fun updateTheme(themeName: String) {
        sharedPrefs.edit().putString("app_theme", themeName).apply()
        _currentTheme.value = themeName
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = DailyEntryRepository(database.dailyEntryDao())
    }

    fun updateDailyTarget(target: Int) {
        sharedPrefs.edit().putInt("daily_target", target).apply()
        _dailyTarget.value = target
    }

    // অল এন্ট্রি ডেটাবেস ফ্লো
    val allEntries: StateFlow<List<DailyEntry>> = repository.allEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ফোল্ডার বা হিসাব খাতা কন্ট্রোল
    private val _customFolders = MutableStateFlow(
        sharedPrefs.getStringSet("custom_folders", setOf("সাধারণ হিসাব"))?.toSet() ?: setOf("সাধারণ হিসাব")
    )
    val customFolders: StateFlow<Set<String>> = _customFolders

    val allFolders: StateFlow<List<String>> = combine(allEntries, _customFolders) { entries, custom ->
        val fromEntries = entries.map { it.folderName }.toSet()
        (listOf("সব হিসাব", "সাধারণ হিসাব") + custom + fromEntries).distinct().sortedBy { if (it == "সব হিসাব") "" else it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("সব হিসাব", "সাধারণ হিসাব"))

    private val _selectedFolder = MutableStateFlow(sharedPrefs.getString("selected_folder", "সব হিসাব") ?: "সব হিসাব")
    val selectedFolder: StateFlow<String> = _selectedFolder

    fun updateSelectedFolder(folderName: String) {
        sharedPrefs.edit().putString("selected_folder", folderName).apply()
        _selectedFolder.value = folderName
    }

    fun addCustomFolder(folderName: String) {
        val trimmed = folderName.trim()
        if (trimmed.isEmpty()) return
        val updated = _customFolders.value.toMutableSet()
        if (updated.add(trimmed)) {
            sharedPrefs.edit().putStringSet("custom_folders", updated).apply()
            _customFolders.value = updated
        }
    }

    fun deleteCustomFolder(folderName: String) {
        val updated = _customFolders.value.toMutableSet()
        if (updated.remove(folderName)) {
            sharedPrefs.edit().putStringSet("custom_folders", updated).apply()
            _customFolders.value = updated
        }
        if (_selectedFolder.value == folderName) {
            updateSelectedFolder("সব হিসাব")
        }
    }

    // নির্বাচিত ফোল্ডারের হিসাব এন্ট্রি
    val filteredEntries: StateFlow<List<DailyEntry>> = combine(allEntries, selectedFolder) { entries, folder ->
        if (folder == "সব হিসাব") {
            entries
        } else {
            entries.filter { it.folderName == folder }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // দৈনিক ও গড় হিসাব সামারি
    val dashboardSummary: StateFlow<DashboardSummary> = filteredEntries
        .combine(MutableStateFlow(Unit)) { entries, _ ->
            var totalPcs = 0
            var totalIncome = 0.0
            var totalExpense = 0.0

            entries.forEach { entry ->
                totalPcs += entry.quantity
                val income = (entry.quantity / 100.0) * 50.0
                totalIncome += income
                totalExpense += entry.expense
            }

            val netProfit = totalIncome - totalExpense
            val totalDays = entries.size

            val avgPcs = if (totalDays > 0) totalPcs.toDouble() / totalDays else 0.0
            val avgExpense = if (totalDays > 0) totalExpense / totalDays else 0.0
            val avgProfit = if (totalDays > 0) netProfit / totalDays else 0.0

            DashboardSummary(
                totalPcs = totalPcs,
                totalIncome = totalIncome,
                totalExpense = totalExpense,
                netProfit = netProfit,
                avgPcs = avgPcs,
                avgExpense = avgExpense,
                avgProfit = avgProfit,
                totalDays = totalDays
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    // CRUD অ্যাকশনসমূহ
    fun addEntry(dateMillis: Long, quantity: Int, expense: Double, note: String, folderName: String = "সাধারণ হিসাব") {
        viewModelScope.launch {
            repository.insert(DailyEntry(dateMillis = dateMillis, quantity = quantity, expense = expense, note = note, folderName = folderName))
        }
    }

    fun updateEntry(id: Int, dateMillis: Long, quantity: Int, expense: Double, note: String, folderName: String = "সাধারণ হিসাব") {
        viewModelScope.launch {
            repository.update(DailyEntry(id = id, dateMillis = dateMillis, quantity = quantity, expense = expense, note = note, folderName = folderName))
        }
    }

    fun deleteEntry(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun exportBackup(): String {
        val entries = allEntries.value
        val sb = java.lang.StringBuilder()
        sb.append("[")
        entries.forEachIndexed { index, entry ->
            sb.append("{")
            sb.append("\"dateMillis\":").append(entry.dateMillis).append(",")
            sb.append("\"quantity\":").append(entry.quantity).append(",")
            sb.append("\"expense\":").append(entry.expense).append(",")
            sb.append("\"folderName\":\"").append(entry.folderName.replace("\"", "\\\"")).append("\",")
            sb.append("\"note\":\"").append(entry.note.replace("\"", "\\\"")).append("\"")
            sb.append("}")
            if (index < entries.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    fun importBackup(backupStr: String): Boolean {
        try {
            val trimmed = backupStr.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false
            val content = trimmed.substring(1, trimmed.length - 1)
            if (content.isEmpty()) return true
            
            val objects = content.split("},{", "} , {", "}, {")
            val parsedEntries = mutableListOf<DailyEntry>()
            
            for (objStr in objects) {
                val cleanObj = objStr.replace("{", "").replace("}", "")
                val pairs = cleanObj.split(",")
                var dateMillis: Long = 0
                var quantity = 0
                var expense = 0.0
                var note = ""
                var folderName = "সাধারণ হিসাব"
                
                for (pair in pairs) {
                    val keyValue = pair.split(":")
                    if (keyValue.size >= 2) {
                        val key = keyValue[0].trim().replace("\"", "")
                        val value = keyValue.drop(1).joinToString(":").trim()
                        when (key) {
                            "dateMillis" -> dateMillis = value.toLongOrNull() ?: 0
                            "quantity" -> quantity = value.toIntOrNull() ?: 0
                            "expense" -> expense = value.toDoubleOrNull() ?: 0.0
                            "note" -> note = value.removeSurrounding("\"").replace("\\\"", "\"")
                            "folderName" -> folderName = value.removeSurrounding("\"").replace("\\\"", "\"")
                        }
                    }
                }
                if (dateMillis > 0 && quantity >= 0) {
                    parsedEntries.add(DailyEntry(dateMillis = dateMillis, quantity = quantity, expense = expense, note = note, folderName = folderName))
                }
            }
            
            if (parsedEntries.isNotEmpty()) {
                viewModelScope.launch {
                    parsedEntries.forEach { repository.insert(it) }
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    companion object {
        fun convertToBanglaDigits(input: String): String {
            val banglaDigits = mapOf(
                '0' to '০', '1' to '১', '2' to '২', '3' to '৩', '4' to '৪',
                '5' to '৫', '6' to '৬', '7' to '৭', '8' to '৮', '9' to '৯'
            )
            return input.map { char -> banglaDigits[char] ?: char }.joinToString("")
        }

        fun formatNumber(value: Double, asPcs: Boolean = false, isCurrency: Boolean = false): String {
            val formatted = if (value % 1.0 == 0.0) {
                value.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", value)
            }
            val bangla = convertToBanglaDigits(formatted)
            return when {
                asPcs -> "$bangla পিস"
                isCurrency -> "৳ $bangla"
                else -> bangla
            }
        }

        fun formatInteger(value: Int, asPcs: Boolean = false): String {
            val bangla = convertToBanglaDigits(value.toString())
            return if (asPcs) "$bangla পিস" else bangla
        }
    }
}

data class DashboardSummary(
    val totalPcs: Int = 0,
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val netProfit: Double = 0.0,
    val avgPcs: Double = 0.0,
    val avgExpense: Double = 0.0,
    val avgProfit: Double = 0.0,
    val totalDays: Int = 0
)

// ============================================================================
// ৪. মেটেরিয়াল ৩ কাস্টম থিম সেকশন (Material 3 Dynamic & Custom Themes)
// ============================================================================

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)
private val LightColorScheme = lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)

private val LiquidGlassColorScheme = lightColorScheme(
    primary = Color(0xFF028090),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F7FA),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = Color(0xFF00A896),
    background = Color(0xFFF0F5F6),
    surface = Color.White,
    onBackground = Color(0xFF1C2D37),
    onSurface = Color(0xFF1C2D37)
)

private val DarkNavyColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF001A20),
    primaryContainer = Color(0xFF0D1B2A),
    onPrimaryContainer = Color(0xFFE0F7FA),
    secondary = Color(0xFF00A8E8),
    background = Color(0xFF050B14),
    surface = Color(0xFF0A1128),
    onBackground = Color(0xFFE6F1F5),
    onSurface = Color(0xFFE6F1F5)
)

@Composable
fun MyApplicationTheme(
    themeName: String = "DEFAULT",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeName) {
        "LIQUID_GLASS" -> LiquidGlassColorScheme
        "DARK_NAVY" -> DarkNavyColorScheme
        else -> {
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// ============================================================================
// ৫. মেইন অ্যাক্টিভিটি এবং UI লেআউট (Main Activity & UI Components)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: DailyEntryViewModel = viewModel()
            val currentTheme by viewModel.currentTheme.collectAsStateWithLifecycle()
            MyApplicationTheme(themeName = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TakaHishabApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakaHishabApp(viewModel: DailyEntryViewModel = viewModel()) {
    val entries by viewModel.filteredEntries.collectAsStateWithLifecycle()
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val summary by viewModel.dashboardSummary.collectAsStateWithLifecycle()
    val dailyTarget by viewModel.dailyTarget.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<DailyEntry?>(null) }
    var entryToDelete by remember { mutableStateOf<DailyEntry?>(null) }
    var showInstructionsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var isNewestFirst by remember { mutableStateOf(true) }
    var showGraph by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = "App Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "টাকা-হিসাব (ডেইলি ট্র্যাকার)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showInstructionsDialog = true }) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.navigationBarsPadding().testTag("add_entry_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Entry", modifier = Modifier.size(28.dp))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp)
                .animateContentSize()
        ) {
            ProductionDashboardCard(summary = summary)
            Spacer(modifier = Modifier.height(10.dp))

            FolderSelectorSection(
                allFolders = allFolders,
                selectedFolder = selectedFolder,
                onFolderSelect = { viewModel.updateSelectedFolder(it) },
                onAddFolder = { viewModel.addCustomFolder(it) },
                onDeleteFolder = { viewModel.deleteCustomFolder(it) }
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().clickable { showGraph = !showGraph }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (showGraph) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle Graph",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (showGraph) "আয়-ব্যয় গ্রাফ লুকান" else "আয়-ব্যয় গ্রাফ দেখান",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(
                visible = showGraph,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                TrendStrengthGraph(entries = entries, modifier = Modifier.padding(bottom = 8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(imageVector = Icons.Default.History, contentDescription = "History", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(text = "দৈনিক হিসাবের ইতিহাস", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "মোট কাজ: ${DailyEntryViewModel.convertToBanglaDigits(summary.totalDays.toString())} দিন", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (entries.isNotEmpty() || searchQuery.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("হিসাব খুঁজুন (যেমন: ২০২৬ বা মন্তব্য)", fontSize = 13.sp) },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    )

                    IconButton(
                        onClick = { isNewestFirst = !isNewestFirst },
                        modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    ) {
                        Icon(imageVector = if (isNewestFirst) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, contentDescription = "Sort", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            val filteredGroupedEntries = remember(entries, searchQuery, isNewestFirst) {
                val sorted = if (isNewestFirst) entries.sortedByDescending { it.dateMillis } else entries.sortedBy { it.dateMillis }
                val filtered = sorted.filter { entry ->
                    if (searchQuery.isBlank()) true
                    else {
                        val formattedDate = formatBengaliDate(entry.dateMillis)
                        formattedDate.contains(searchQuery, ignoreCase = true) ||
                        entry.note.contains(searchQuery, ignoreCase = true) ||
                        entry.quantity.toString().contains(searchQuery) ||
                        entry.expense.toString().contains(searchQuery) ||
                        DailyEntryViewModel.convertToBanglaDigits(entry.quantity.toString()).contains(searchQuery) ||
                        DailyEntryViewModel.convertToBanglaDigits(entry.expense.toString()).contains(searchQuery)
                    }
                }
                filtered.groupBy { SimpleDateFormat("MMMM yyyy", Locale("bn", "BD")).format(Date(it.dateMillis)) }
            }

            if (entries.isEmpty() && searchQuery.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(imageVector = Icons.Default.CloudOff, contentDescription = "No Data", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                        Text(text = "এই ফোল্ডারে কোনো হিসাব যুক্ত করা হয়নি।\nনিচের '+' বাটনে ক্লিক করে আজকের কাজ যোগ করুন!", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (filteredGroupedEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(text = "কোনো মিল পাওয়া যায়নি!", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    filteredGroupedEntries.forEach { (monthName, monthEntries) ->
                        item(key = monthName) {
                            Text(text = monthName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp))
                        }
                        items(monthEntries, key = { it.id }) { entry ->
                            HistoryRowItem(entry = entry, onEditClick = { entryToEdit = entry }, onDeleteClick = { entryToDelete = entry })
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditEntryDialog(
            entry = null, dailyTarget = dailyTarget, allFolders = allFolders, initialFolder = selectedFolder,
            onDismiss = { showAddDialog = false },
            onSave = { dateMillis, quantity, expense, note, folder ->
                viewModel.addEntry(dateMillis, quantity, expense, note, folder)
                showAddDialog = false
            }
        )
    }

    if (entryToEdit != null) {
        AddEditEntryDialog(
            entry = entryToEdit, dailyTarget = dailyTarget, allFolders = allFolders, initialFolder = selectedFolder,
            onDismiss = { entryToEdit = null },
            onSave = { dateMillis, quantity, expense, note, folder ->
                entryToEdit?.let { viewModel.updateEntry(it.id, dateMillis, quantity, expense, note, folder) }
                entryToEdit = null
            }
        )
    }

    if (entryToDelete != null) {
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("হিসাব মুছে ফেলুন", fontWeight = FontWeight.Bold) },
            text = { Text("আপনি কি নিশ্চিত যে এই দিনের হিসাবটি ডিলিট করতে চান?") },
            confirmButton = {
                Button(
                    onClick = {
                        entryToDelete?.let { viewModel.deleteEntry(it.id) }
                        entryToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("হ্যাঁ, ডিলিট করুন") }
            },
            dismissButton = { TextButton(onClick = { entryToDelete = null }) { Text("বাতিল") } }
        )
    }

    if (showInstructionsDialog) {
        InstructionsDialog(onDismiss = { showInstructionsDialog = false })
    }

    if (showSettingsDialog) {
        val currentTheme by viewModel.currentTheme.collectAsStateWithLifecycle()
        SettingsDialog(
            currentDailyTarget = dailyTarget, currentTheme = currentTheme,
            onUpdateTheme = { viewModel.updateTheme(it) },
            onUpdateDailyTarget = { viewModel.updateDailyTarget(it) },
            onExportBackup = { viewModel.exportBackup() },
            onImportBackup = { viewModel.importBackup(it) },
            onClearAllData = { viewModel.clearAllData() },
            onDismiss = { showSettingsDialog = false },
            entries = entries, summary = summary, selectedFolder = selectedFolder
        )
    }
}

@Composable
fun ProductionDashboardCard(summary: DashboardSummary) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(imageVector = Icons.Default.Summarize, contentDescription = "Summary", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Text(text = "মোট হিসাবসমূহ", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                DashboardItem(title = "মোট মাল", value = DailyEntryViewModel.formatInteger(summary.totalPcs, asPcs = true), icon = Icons.Default.Layers, colorAccent = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                DashboardItem(title = "মোট আয়", value = DailyEntryViewModel.formatNumber(summary.totalIncome, isCurrency = true), icon = Icons.Default.Payments, colorAccent = Color(0xFF2E7D32), modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                DashboardItem(title = "মোট খরচ", value = DailyEntryViewModel.formatNumber(summary.totalExpense, isCurrency = true), icon = Icons.Default.LocalAtm, colorAccent = Color(0xFFC62828), modifier = Modifier.weight(1f))
                DashboardItem(title = "বাকি (নিট লাভ)", value = DailyEntryViewModel.formatNumber(summary.netProfit, isCurrency = true), icon = Icons.Default.AccountBalanceWallet, colorAccent = if (summary.netProfit >= 0) Color(0xFF2E7D32) else Color(0xFFC62828), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(imageVector = Icons.Default.Analytics, contentDescription = "Analytics", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Text(text = "দৈনিক গড় হিসাব (মোট কাজের দিন দিয়ে ভাগ)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "গড় মাল", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(text = DailyEntryViewModel.formatNumber(summary.avgPcs, asPcs = true), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Box(modifier = Modifier.height(24.dp).width(1.dp).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "গড় খরচ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(text = DailyEntryViewModel.formatNumber(summary.avgExpense, isCurrency = true), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFC62828))
                }
                Box(modifier = Modifier.height(24.dp).width(1.dp).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "গড় লাভ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(text = DailyEntryViewModel.formatNumber(summary.avgProfit, isCurrency = true), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (summary.avgProfit >= 0) Color(0xFF2E7D32) else Color(0xFFC62828))
                }
            }
        }
    }
}

@Composable
fun DashboardItem(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, colorAccent: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(colorAccent.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = title, tint = colorAccent, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            Text(text = value, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = colorAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HistoryRowItem(entry: DailyEntry, onEditClick: () -> Unit, onDeleteClick: () -> Unit) {
    val income = (entry.quantity / 100.0) * 50.0
    val netProfit = income - entry.expense
    val isProfit = netProfit >= 0

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEditClick).testTag("entry_item_${entry.id}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = formatBengaliDate(entry.dateMillis), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (isProfit) Color(0xFF2E7D32).copy(alpha = 0.08f) else Color(0xFFC62828).copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = if (isProfit) "✔ লাভ" else "✖ লোকসান", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = if (isProfit) Color(0xFF2E7D32) else Color(0xFFC62828))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "মাল: ${DailyEntryViewModel.formatInteger(entry.quantity, asPcs = true)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "•", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Text(text = "আয়: ${DailyEntryViewModel.formatNumber(income, isCurrency = true)}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2E7D32))
                    Text(text = "•", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Text(text = "খরচ: ${DailyEntryViewModel.formatNumber(entry.expense, isCurrency = true)}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFC62828))
                }
                if (entry.note.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(text = "মন্তব্য: ${entry.note}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onEditClick, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun AddEditEntryDialog(
    entry: DailyEntry?, dailyTarget: Int, allFolders: List<String>, initialFolder: String,
    onDismiss: () -> Unit,
    onSave: (Long, Int, Double, String, String) -> Unit
) {
    val context = LocalContext.current
    var dateMillis by remember { mutableStateOf(entry?.dateMillis ?: System.currentTimeMillis()) }
    var quantityInput by remember { mutableStateOf(entry?.quantity?.toString() ?: "") }
    var expenseInput by remember { mutableStateOf(entry?.expense?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var noteInput by remember { mutableStateOf(entry?.note ?: "") }
    var selectedFolderOption by remember { mutableStateOf(entry?.folderName ?: (if (initialFolder == "সব হিসাব") "সাধারণ হিসাব" else initialFolder)) }
    var folderExpanded by remember { mutableStateOf(false) }

    var quantityError by remember { mutableStateOf<String?>(null) }
    var expenseError by remember { mutableStateOf<String?>(null) }

    val parsedQuantity = quantityInput.toIntOrNull() ?: 0
    val targetRemains = (dailyTarget - parsedQuantity).coerceAtLeast(0)
    val isTargetReached = parsedQuantity >= dailyTarget

    val dateString = remember(dateMillis) { SimpleDateFormat("dd MMMM yyyy", Locale("bn", "BD")).format(Date(dateMillis)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (entry == null) "নতুন দৈনিক হিসাব যুক্ত করুন" else "দৈনিক হিসাব পরিবর্তন করুন",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "তারিখ নির্বাচন করুন", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = {
                            val calendar = Calendar.getInstance()
                            calendar.timeInMillis = dateMillis
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val selected = Calendar.getInstance()
                                    selected.set(year, month, dayOfMonth)
                                    dateMillis = selected.timeInMillis
                                },
                                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.CalendarToday, contentDescription = "Calendar")
                            Text(text = dateString, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { dateMillis = System.currentTimeMillis() },
                            label = { Text("আজ") },
                            leadingIcon = { Icon(Icons.Default.Today, null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR) {
                            AssistChip(
                                onClick = { dateMillis = System.currentTimeMillis() - 24 * 60 * 60 * 1000 },
                                label = { Text("গতকাল") },
                                leadingIcon = { Icon(Icons.Default.Event, null, modifier = Modifier.size(14.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = quantityInput,
                        onValueChange = {
                            quantityInput = it
                            if (it.toIntOrNull() != null) quantityError = null
                        },
                        label = { Text("মালের পরিমাণ (পিস)") },
                        placeholder = { Text("যেমন: ৮৫০") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = quantityError != null,
                        supportingText = { quantityError?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("entry_quantity_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(100, 500, 800).forEach { amount ->
                            AssistChip(
                                onClick = {
                                    quantityInput = amount.toString()
                                    quantityError = null
                                },
                                label = { Text("+${DailyEntryViewModel.convertToBanglaDigits(amount.toString())} পিস") }
                            )
                        }
                    }
                }

                // লাইভ টার্গেট ট্র্যাকার
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)).padding(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(text = "আজকের টার্গেট: ${DailyEntryViewModel.convertToBanglaDigits(dailyTarget.toString())} পিস", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (isTargetReached) {
                                Text(text = "🎉 টার্গেট সম্পূর্ণ হয়েছে!", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            } else {
                                Text(text = "বাকি আছে: ${DailyEntryViewModel.convertToBanglaDigits(targetRemains.toString())} পিস", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                            }
                        }
                        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(if (isTargetReached) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(imageVector = if (isTargetReached) Icons.Default.Check else Icons.Default.TrendingUp, contentDescription = "Target Status", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                OutlinedTextField(
                    value = expenseInput,
                    onValueChange = {
                        expenseInput = it
                        if (it.toDoubleOrNull() != null || it.isEmpty()) expenseError = null
                    },
                    label = { Text("দৈনিক খরচ (টাকা) - ঐচ্ছিক") },
                    placeholder = { Text("যেমন: ১২০") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = expenseError != null,
                    supportingText = { expenseError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // খাতা বা ফোল্ডার ড্রপডাউন নির্বাচন
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "হিসাবের খাতা (ফোল্ডার) নির্বাচন করুন", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { folderExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = selectedFolderOption, fontWeight = FontWeight.SemiBold)
                                Icon(imageVector = if (folderExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = "Expand")
                            }
                        }
                        DropdownMenu(expanded = folderExpanded, onDismissRequest = { folderExpanded = false }, modifier = Modifier.fillMaxWidth(0.85f)) {
                            allFolders.filter { it != "সব হিসাব" }.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder) },
                                    onClick = {
                                        selectedFolderOption = folder
                                        folderExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("মন্তব্য (যেমন: কাজের বিবরণ বা অগ্রিম)") },
                    placeholder = { Text("মন্তব্য লিখুন...") },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("বাতিল") }
                    Button(
                        onClick = {
                            val qty = quantityInput.toIntOrNull()
                            val exp = expenseInput.toDoubleOrNull() ?: 0.0
                            if (qty == null || qty < 0) {
                                quantityError = "সঠিক মালের সংখ্যা দিন"
                            } else {
                                onSave(dateMillis, qty, exp, noteInput.trim(), selectedFolderOption)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("সংরক্ষণ করুন") }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    currentDailyTarget: Int, currentTheme: String,
    onUpdateTheme: (String) -> Unit,
    onUpdateDailyTarget: (Int) -> Unit,
    onExportBackup: () -> String,
    onImportBackup: (String) -> Boolean,
    onClearAllData: () -> Unit,
    onDismiss: () -> Unit,
    entries: List<DailyEntry>, summary: DashboardSummary, selectedFolder: String
) {
    val context = LocalContext.current
    var targetInput by remember { mutableStateOf(currentDailyTarget.toString()) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var importInput by remember { mutableStateOf("") }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isImportSuccess by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var backupString by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = "সেটিংস ও ব্যাকআপ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                // থিম সিলেকশন কার্ড
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "১. অ্যাপের থিম পরিবর্তন করুন", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("DEFAULT" to "ডিফল্ট", "LIQUID_GLASS" to "ফিরোজা", "DARK_NAVY" to "নেভি ব্লু").forEach { (key, label) ->
                            val isSelected = currentTheme == key
                            OutlinedButton(
                                onClick = { onUpdateTheme(key) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.weight(1f)
                            ) { Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // টার্গেট পরিবর্তন সেকশন
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "১.২. দৈনিক কাজের টার্গেট পরিবর্তন", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = targetInput,
                            onValueChange = {
                                targetInput = it
                                if (it.toIntOrNull() != null) targetError = null
                            },
                            label = { Text("টার্গেট (পিস)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = targetError != null,
                            supportingText = { targetError?.let { Text(it) } },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Button(
                            onClick = {
                                val target = targetInput.toIntOrNull()
                                if (target == null || target <= 0) {
                                    targetError = "সব ঠিক করে দিন"
                                } else {
                                    onUpdateDailyTarget(target)
                                    targetError = null
                                    Toast.makeText(context, "টার্গেট সফলভাবে পরিবর্তন হয়েছে!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.8f).height(56.dp)
                        ) { Text("সেভ") }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // ব্যাকআপ এক্সপোর্ট
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "২. অফলাইন ব্যাকআপ কোড রপ্তানি (Export Backup)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = "সব ডেটা একটি ব্যাকআপ ফাইল কোড আকারে তৈরি করতে নিচের বাটনে চাপ দিন।", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            val code = onExportBackup()
                            backupString = code
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("TakaHishabBackup", code)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "কোড ক্লিপবোর্ডে কপি করা হয়েছে!", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                            Text("ব্যাকআপ কোড কপি ও শেয়ার করুন")
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // গুগল কিপ ব্যাকআপ
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "২.২ গুগল কিপ নোটে ব্যাকআপ (Google Keep Backup)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = "আপনার হিসাব খাতার ($selectedFolder) বিস্তারিত বাংলা রিপোর্ট এবং রিস্টোর কোড সরাসরি গুগল কিপ নোটে সেভ করুন।", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            val backupCode = onExportBackup()
                            val dateStr = formatBengaliDate(System.currentTimeMillis())
                            val report = StringBuilder().apply {
                                append("📌 **টাকা-হিসাব খাতা ব্যাকআপ (Google Keep)**\n")
                                append("• হিসাবের খাতা: $selectedFolder\n")
                                append("• ব্যাকআপ তারিখ: $dateStr\n")
                                append("• মোট হিসাব ভুক্তি: ${DailyEntryViewModel.convertToBanglaDigits(entries.size.toString())} টি\n\n")
                                if (entries.isNotEmpty()) {
                                    append("📋 **লেনদেন ও কাজের সম্পূর্ণ ইতিহাস:**\n")
                                    val sortedAll = entries.sortedByDescending { it.dateMillis }
                                    sortedAll.forEachIndexed { idx, entry ->
                                        val indexBng = DailyEntryViewModel.convertToBanglaDigits((idx + 1).toString())
                                        val noteText = if (entry.note.isNotEmpty()) " (${entry.note})" else ""
                                        append("$indexBng. ${formatBengaliDate(entry.dateMillis)}: মাল ${DailyEntryViewModel.formatInteger(entry.quantity, asPcs = false)} পিস | খরচ: ${DailyEntryViewModel.formatNumber(entry.expense, isCurrency = false)} টাকা$noteText\n")
                                    }
                                    append("\n")
                                }
                                append("🔄 **অ্যাপ রিস্টোর কোড (Restore Code):**\n")
                                append("----------------------------------------\n")
                                append(backupCode)
                                append("\n----------------------------------------\n")
                            }

                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TakaHishabKeepBackup", report.toString()))

                            try {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "টাকা-হিসাব ব্যাকআপ - $selectedFolder")
                                    putExtra(android.content.Intent.EXTRA_TEXT, report.toString())
                                    type = "text/plain"
                                    `package` = "com.google.android.keep"
                                }
                                context.startActivity(sendIntent)
                            } catch (e: Exception) {
                                val genericIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, report.toString())
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(genericIntent, "গুগল কিপ বা নোটে শেয়ার করুন"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F))
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.NoteAdd, contentDescription = "Keep", tint = Color.Black)
                            Text("গুগল কিপ-এ ব্যাকআপ নোট তৈরি করুন", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // ব্যাকআপ ইমপোর্ট
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "৩. ব্যাকআপ কোড আমদানি (Import Backup)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = {
                            importInput = it
                            importStatusMessage = null
                        },
                        placeholder = { Text("এখানে ব্যাকআপ কোড পেস্ট করুন...") },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            if (importInput.isBlank()) {
                                importStatusMessage = "অনুগ্রহ করে কোড পেস্ট করুন"
                                isImportSuccess = false
                            } else {
                                val success = onImportBackup(importInput)
                                if (success) {
                                    importStatusMessage = "✔ ব্যাকআপ রিস্টোর সফল হয়েছে!"
                                    isImportSuccess = true
                                    importInput = ""
                                } else {
                                    importStatusMessage = "✖ ব্যাকআপ কোডটি সঠিক নয়!"
                                    isImportSuccess = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("কোড আমদানি করুন") }

                    importStatusMessage?.let { msg ->
                        Text(text = msg, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isImportSuccess) Color(0xFF2E7D32) else Color(0xFFC62828))
                    }
                }

                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // সব ডেটা ক্লিয়ার
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "৪. সব তথ্য মুছুন (Clear All Data)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.DeleteForever, contentDescription = "Clear")
                            Text("সব অফলাইন হিসাব ডিলিট করুন")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("ঠিক আছে") }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("সব হিসাব মুছুন", fontWeight = FontWeight.Bold) },
            text = { Text("আপনি কি নিশ্চিত যে ডাটাবেসের সব হিসাব সম্পূর্ণভাবে মুছে ফেলতে চান? এটি আর ফিরিয়ে আনা যাবে না।") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllData()
                        showClearConfirm = false
                        Toast.makeText(context, "সব ডেটা সম্পূর্ণ মুছে ফেলা হয়েছে!", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("হ্যাঁ, সব মুছুন") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("বাতিল") } }
        )
    }
}

@Composable
fun InstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = Icons.Default.Help, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary)
                Text("ব্যবহারের নিয়মাবলী", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "টাকা-হিসাব অ্যাপটির মূল হিসাবের সূত্রসমূহ নিচে দেওয়া হলো:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                BulletItem("১. আয় হিসাব:", "প্রতি ১০০ পিস মালের দাম ৫০ টাকা ধরা হয়েছে। অর্থাৎ, দৈনিক আয় = (মালের পরিমাণ ÷ ১০০) × ৫০।")
                BulletItem("২. নিট লাভ/বাকি:", "মোট আয় থেকে দৈনিক খরচ বিয়োগ করে নিট লাভ পাওয়া যায়। (নিট লাভ = আয় - খরচ)।")
                BulletItem("৩. দৈনিক টার্গেট:", "সেটিংস থেকে দৈনিক টার্গেট পিস পরিবর্তন করতে পারেন (ডিফল্ট ৮০০)। মালের সংখ্যা লেখার সাথে সাথে টার্গেট কত বাকি আছে তা রিয়েল-টাইমে দেখাবে।")
                BulletItem("৪. সম্পূর্ণ অফলাইন ও ব্যাকআপ:", "অ্যাপটি অফলাইনে কাজ করে। ডাটা সুরক্ষিত রাখতে সেটিংস থেকে ব্যাকআপ কোড কপি বা গুগল কিপে শেয়ার করে রাখতে পারেন।")
                BulletItem("৫. লোকাল ইনস্টলেশন ও APK:", "অ্যাপটি লোকাল অ্যান্ড্রয়েড মোবাইলে ইনস্টল করতে AI Studio এর ডানদিকের থ্রি-ডট মেনু বা গিয়ার থেকে 'Download APK' অপশনে ক্লিক করুন।")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("ঠিক আছে") } }
    )
}

@Composable
fun BulletItem(title: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Text(text = description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatBengaliDate(timestamp: Long): String {
    return SimpleDateFormat("dd MMMM yyyy", Locale("bn", "BD")).format(Date(timestamp))
}

@Composable
fun FolderSelectorSection(
    allFolders: List<String>, selectedFolder: String,
    onFolderSelect: (String) -> Unit, onAddFolder: (String) -> Unit, onDeleteFolder: (String) -> Unit
) {
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Folders", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(text = "হিসাব খাতা / ফোল্ডারসমূহ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = { showAddFolderDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }

        LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(allFolders) { folder ->
                val isSelected = folder == selectedFolder
                val isCustom = folder != "সব হিসাব" && folder != "সাধারণ হিসাব"
                AssistChip(
                    onClick = { onFolderSelect(folder) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = folder, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                            if (isCustom && isSelected) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp).clickable { onDeleteFolder(folder) })
                            }
                        }
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = BorderStroke(width = if (isSelected) 1.5.dp else 1.dp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }

    if (showAddFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddFolderDialog = false
                newFolderName = ""
            },
            title = { Text("নতুন হিসাব খাতা / ফোল্ডার", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("হিসাব আলাদা করার জন্য একটি খাতার নাম দিন (যেমন: ফ্যাক্টরি ১, জুলাই ২০২৬):", fontSize = 13.sp)
                    OutlinedTextField(
                        value = newFolderName, onValueChange = { newFolderName = it },
                        placeholder = { Text("খাতার নাম লিখুন...") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onAddFolder(newFolderName.trim())
                            onFolderSelect(newFolderName.trim())
                        }
                        showAddFolderDialog = false
                        newFolderName = ""
                    }
                ) { Text("তৈরি করুন") }
            },
            dismissButton = { TextButton(onClick = { showAddFolderDialog = false; newFolderName = "" }) { Text("বাতিল") } }
        )
    }
}

@Composable
fun TrendStrengthGraph(entries: List<DailyEntry>, modifier: Modifier = Modifier) {
    val sortedEntries = remember(entries) { entries.sortedBy { it.dateMillis }.takeLast(7) }

    Card(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(imageVector = Icons.Default.BarChart, contentDescription = "Graph", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(text = "আয় বনাম ব্যয় ট্রেন্ড গ্রাফ (সর্বশেষ ৭ দিন)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF2E7D32)))
                        Text("আয়", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFC62828)))
                        Text("ব্যয়", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (sortedEntries.size < 2) {
                Box(modifier = Modifier.fillMaxWidth().height(115.dp), contentAlignment = Alignment.Center) {
                    Text(text = "গ্রাফ দেখার জন্য কমপক্ষে ২ দিনের হিসাব যুক্ত করুন", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            } else {
                val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(modifier = Modifier.fillMaxWidth().height(115.dp).padding(horizontal = 8.dp, vertical = 12.dp)) {
                    val width = size.width
                    val height = size.height

                    val maxVal = sortedEntries.maxOf { entry ->
                        val income = (entry.quantity / 100.0) * 50.0
                        maxOf(income, entry.expense)
                    }.coerceAtLeast(100.0) * 1.15

                    val stepX = width / (sortedEntries.size - 1)
                    val pointsIncome = mutableListOf<androidx.compose.ui.geometry.Offset>()
                    val pointsExpense = mutableListOf<androidx.compose.ui.geometry.Offset>()

                    sortedEntries.forEachIndexed { index, entry ->
                        val income = (entry.quantity / 100.0) * 50.0
                        val expense = entry.expense
                        val x = index * stepX
                        val yIncome = (height - (income / maxVal) * height).toFloat()
                        val yExpense = (height - (expense / maxVal) * height).toFloat()
                        pointsIncome.add(androidx.compose.ui.geometry.Offset(x, yIncome))
                        pointsExpense.add(androidx.compose.ui.geometry.Offset(x, yExpense))
                    }

                    for (i in 0..3) {
                        val y = (height / 3) * i
                        drawLine(
                            color = onSurfaceVariantColor.copy(alpha = 0.08f),
                            start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(width, y),
                            strokeWidth = 1f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    }

                    val pathIncome = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pointsIncome.first().x, pointsIncome.first().y)
                        for (i in 1 until pointsIncome.size) lineTo(pointsIncome[i].x, pointsIncome[i].y)
                    }
                    val pathIncomeArea = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pointsIncome.first().x, height)
                        lineTo(pointsIncome.first().x, pointsIncome.first().y)
                        for (i in 1 until pointsIncome.size) lineTo(pointsIncome[i].x, pointsIncome[i].y)
                        lineTo(pointsIncome.last().x, height)
                        close()
                    }

                    val pathExpense = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pointsExpense.first().x, pointsExpense.first().y)
                        for (i in 1 until pointsExpense.size) lineTo(pointsExpense[i].x, pointsExpense[i].y)
                    }
                    val pathExpenseArea = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pointsExpense.first().x, height)
                        lineTo(pointsExpense.first().x, pointsExpense.first().y)
                        for (i in 1 until pointsExpense.size) lineTo(pointsExpense[i].x, pointsExpense[i].y)
                        lineTo(pointsExpense.last().x, height)
                        close()
                    }

                    drawPath(path = pathIncomeArea, brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(Color(0xFF2E7D32).copy(alpha = 0.15f), Color.Transparent)))
                    drawPath(path = pathExpenseArea, brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(Color(0xFFC62828).copy(alpha = 0.15f), Color.Transparent)))

                    drawPath(path = pathIncome, color = Color(0xFF2E7D32), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawPath(path = pathExpense, color = Color(0xFFC62828), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round))

                    pointsIncome.forEach { pt ->
                        drawCircle(color = Color(0xFF2E7D32), radius = 4.dp.toPx(), center = pt)
                        drawCircle(color = Color.White, radius = 2.dp.toPx(), center = pt)
                    }
                    pointsExpense.forEach { pt ->
                        drawCircle(color = Color(0xFFC62828), radius = 4.dp.toPx(), center = pt)
                        drawCircle(color = Color.White, radius = 2.dp.toPx(), center = pt)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    sortedEntries.forEach { entry ->
                        val dateStr = SimpleDateFormat("dd/MM", Locale("bn", "BD")).format(Date(entry.dateMillis))
                        Text(text = DailyEntryViewModel.convertToBanglaDigits(dateStr), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}
