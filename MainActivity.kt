package com.example.scheduler
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*
// DATABASE ENTITY ---
enum class Screen { LIST, CALENDAR }
enum class Priority(val label: String, val color: Color) {
    NONE("No Priority", Color.Gray),
    LOW("Low", Color(0xFF4CAF50)),
    MEDIUM("Medium", Color(0xFFFFA000)),
    HIGH("High", Color(0xFFD32F2F))
}
@Entity(tableName = "tasks")
data class TodoTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val isDone: Boolean = false,
    val dueDate: Long? = null,
    val priority: Priority = Priority.NONE,
    val category: String = "",
    val isPinned: Boolean = false
)
// DAO ---
@Dao
interface TaskDao {
    @Query("""
       SELECT * FROM tasks
       ORDER BY isPinned DESC,
       CASE priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 ELSE 4 END ASC
   """)
    fun getAllTasks(): Flow<List<TodoTask>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TodoTask)
    @Update
    suspend fun updateTask(task: TodoTask)
    @Delete
    suspend fun deleteTask(task: TodoTask)
    @Query("DELETE FROM tasks WHERE isDone = 1")
    suspend fun deleteCompleted()
    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
//DATABASE ---
@Database(entities = [TodoTask::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "scheduler_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
// VIEWMODEL ---
class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).taskDao()
    val tasks = dao.getAllTasks()
    private var recentlyDeletedTasks: List<TodoTask> = emptyList()
    fun addTask(title: String, dueDate: Long?, priority: Priority, category: String) = viewModelScope.launch {
        dao.insertTask(TodoTask(title = title, dueDate = dueDate, priority = priority, category = category))
    }
    fun toggleTask(task: TodoTask) = viewModelScope.launch { dao.updateTask(task.copy(isDone = !task.isDone)) }
    fun togglePin(task: TodoTask) = viewModelScope.launch { dao.updateTask(task.copy(isPinned = !task.isPinned)) }
    fun deleteTask(task: TodoTask) = viewModelScope.launch { recentlyDeletedTasks = listOf(task); dao.deleteTask(task) }
    fun deleteCompleted() = viewModelScope.launch {
        recentlyDeletedTasks = tasks.first().filter { it.isDone }
        dao.deleteCompleted()
    }
    fun clearAll() = viewModelScope.launch {
        recentlyDeletedTasks = tasks.first()
        dao.deleteAll()
    }
    fun undoDelete() = viewModelScope.launch {
        recentlyDeletedTasks.forEach { dao.insertTask(it) }
        recentlyDeletedTasks = emptyList()
    }
    class Factory(private val app: Application) : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return TaskViewModel(app) as T
        }
    }
}
//UI HELPERS ---
private fun formatDueDate(dueDate: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = dueDate }
    val isMidnight = calendar.get(Calendar.HOUR_OF_DAY) == 0 && calendar.get(Calendar.MINUTE) == 0
    val format = if (isMidnight) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
    } else {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    }
    return format.format(dueDate)
}
// MAIN UI SCREEN ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(
    taskList: List<TodoTask>,
    onAdd: (String, Long?, Priority, String) -> Unit,
    onToggle: (TodoTask) -> Unit,
    onTogglePin: (TodoTask) -> Unit,
    onDelete: (TodoTask) -> Unit,
    onClearDone: () -> Unit,
    onClearAll: () -> Unit,
    onUndo: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var currentScreen by remember { mutableStateOf(Screen.LIST) }
    val scope = rememberCoroutineScope()
    // UI State
    var taskText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterCategory by remember { mutableStateOf("All") }
    // Task Creation State
    var categoryText by remember { mutableStateOf("") }
    var selectedPriority by remember { mutableStateOf(Priority.NONE) }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    // Visibility States
    var showMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var priorityExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()
    val timePickerState = rememberTimePickerState()
    val snackbarHostState = remember { SnackbarHostState() }
    val categorySuggestions = listOf("Work", "Personal", "Shopping", "Health", "Urgent")
    val filteredTasks = taskList.filter { task ->
        val matchesSearch = task.title.contains(searchQuery, ignoreCase = true)
        val matchesCategory = selectedFilterCategory == "All" || task.category == selectedFilterCategory
        matchesSearch && matchesCategory
    }
    fun combineDateTime(dateMillis: Long?, hour: Int?, minute: Int?): Long? {
        if (dateMillis == null) return null
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMillis }
        return Calendar.getInstance().apply {
            set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH), hour ?: 0, minute ?: 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateMillis = datePickerState.selectedDateMillis
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Next") }
            }
        ) { DatePicker(state = datePickerState) }
    }
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateMillis = combineDateTime(selectedDateMillis, timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text("Set Time") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedDateMillis = combineDateTime(selectedDateMillis, 0, 0)
                    showTimePicker = false
                }) { Text("No Time") }
            },
            title = { Text("Select Time") },
            text = { TimePicker(state = timePickerState) }
        )
    }
    // --- BURGER MENU WRAPPER ---
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("Task List") },
                    selected = currentScreen == Screen.LIST,
                    onClick = { currentScreen = Screen.LIST; scope.launch { drawerState.close() } },
                    icon = { Icon(Icons.Default.List, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Calendar View") },
                    selected = currentScreen == Screen.CALENDAR,
                    onClick = { currentScreen = Screen.CALENDAR; scope.launch { drawerState.close() } },
                    icon = { Icon(Icons.Default.DateRange, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(if (currentScreen == Screen.LIST) "Task List" else "Calendar") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Delete Completed") }, onClick = { onClearDone(); showMenu = false })
                                DropdownMenuItem(text = { Text("Clear All") }, onClick = { onClearAll(); showMenu = false })
                            }
                        }
                    )
                    if (currentScreen == Screen.LIST) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            placeholder = { Text("Search tasks...") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            shape = MaterialTheme.shapes.medium
                        )
                        val distinctCategories = listOf("All") + taskList.map { it.category }.filter { it.isNotBlank() }.distinct()
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp)) {
                            distinctCategories.forEach { cat ->
                                FilterChip(
                                    selected = selectedFilterCategory == cat,
                                    onClick = { selectedFilterCategory = cat },
                                    label = { Text(cat) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                    Column(modifier = Modifier.padding(16.dp).navigationBarsPadding().imePadding()) {
                        if (selectedDateMillis != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Due: ${formatDueDate(selectedDateMillis!!)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = { selectedDateMillis = null }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Clear, null, tint = Color.Red)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = taskText,
                                onValueChange = { taskText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Add a task...") },
                                trailingIcon = {
                                    IconButton(onClick = { showDatePicker = true }) {
                                        Icon(Icons.Default.DateRange, null)
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            FloatingActionButton(
                                onClick = {
                                    if (taskText.isNotBlank()) {
                                        onAdd(taskText, selectedDateMillis, selectedPriority, categoryText)
                                        // Reset everything
                                        taskText = ""; selectedDateMillis = null; categoryText = ""; selectedPriority = Priority.NONE
                                    }
                                }
                            ) { Icon(Icons.Default.Add, null) }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                AssistChip(
                                    onClick = { priorityExpanded = true },
                                    label = { Text(selectedPriority.label) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Info, null,
                                            tint = if(selectedPriority == Priority.NONE) Color.Gray else selectedPriority.color)
                                    }
                                )
                                DropdownMenu(expanded = priorityExpanded, onDismissRequest = { priorityExpanded = false }) {
                                    Priority.values().forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.label) },
                                            leadingIcon = { Icon(Icons.Default.Brightness1, null, tint = p.color) },
                                            onClick = { selectedPriority = p; priorityExpanded = false }
                                        )
                                    }
                                }
                            }
                            Box {
                                AssistChip(
                                    onClick = { categoryExpanded = true },
                                    label = { Text(if(categoryText.isBlank()) "No Category" else categoryText) },
                                    leadingIcon = { Icon(Icons.Default.Label, null) },
                                    trailingIcon = {
                                        if (categoryText.isNotBlank()) {
                                            IconButton(onClick = { categoryText = "" }, modifier = Modifier.size(18.dp)) {
                                                Icon(Icons.Default.Close, null)
                                            }
                                        }
                                    }
                                )
                                DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                                    // Allow user to type a new category
                                    OutlinedTextField(
                                        value = categoryText,
                                        onValueChange = { categoryText = it },
                                        label = { Text("Type Category") },
                                        modifier = Modifier.padding(8.dp).width(150.dp),
                                        singleLine = true
                                    )
                                    HorizontalDivider()
                                    // Show suggestions
                                    categorySuggestions.forEach { suggestion ->
                                        DropdownMenuItem(
                                            text = { Text(suggestion) },
                                            onClick = { categoryText = suggestion; categoryExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (currentScreen == Screen.LIST) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = filteredTasks, key = { it.id }) { task ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        onDelete(task)
                                        scope.launch { snackbarHostState.showSnackbar("Deleted ${task.title}") }
                                        true
                                    } else false
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = { DismissBackground(dismissState) },
                                enableDismissFromStartToEnd = false
                            ) {
                                TaskItem(task, { onToggle(task) }, { onTogglePin(task) }, { onDelete(task) })
                            }
                        }
                    }
                } else {
                    CalendarView(taskList)
                }
            }
        }
    }
}
@Composable
fun DismissBackground(dismissState: SwipeToDismissBoxState) {
    val color = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.EndToStart -> Color(0xFFD32F2F) // Red for delete
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(color, shape = MaterialTheme.shapes.medium),
        contentAlignment = Alignment.CenterEnd
    ) {
        if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
    }
}
@Composable
fun TaskItem(task: TodoTask, onToggle: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).alpha(if (task.isDone) 0.6f else 1f),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isPinned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.isDone, onCheckedChange = { onToggle() })
            Surface(
                modifier = Modifier.width(4.dp).height(32.dp).padding(vertical = 4.dp),
                color = task.priority.color,
                shape = MaterialTheme.shapes.small
            ) {}
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                if (task.category.isNotBlank()) {
                    Text(task.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null
                    )
                )
                if (task.dueDate != null) {
                    Text(formatDueDate(task.dueDate), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            IconButton(onClick = onPin) {
                Icon(
                    imageVector = if (task.isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Pin Task",
                    tint = if (task.isPinned) Color(0xFFFFA000) else Color.Gray
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = Color.Gray)
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarView(taskList: List<TodoTask>) {
    val calendarState = rememberDatePickerState()
    val selectedDate = calendarState.selectedDateMillis
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        DatePicker(
            state = calendarState,
            showModeToggle = false,
            title = null,
            headline = null
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "Tasks for this date:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val dailyTasks = taskList.filter { task ->
            if (task.dueDate == null || selectedDate == null) false
            else {
                val taskCal = Calendar.getInstance().apply { timeInMillis = task.dueDate }
                val selectedCal = Calendar.getInstance().apply { timeInMillis = selectedDate }
                taskCal.get(Calendar.YEAR) == selectedCal.get(Calendar.YEAR) &&
                        taskCal.get(Calendar.DAY_OF_YEAR) == selectedCal.get(Calendar.DAY_OF_YEAR)
            }
        }
        if (dailyTasks.isEmpty()) {
            Text("No tasks due on this day.", color = Color.Gray)
        } else {
            LazyColumn {
                items(dailyTasks) { task ->
                    ListItem(
                        headlineContent = { Text(task.title) },
                        supportingContent = { Text(task.category) },
                        leadingContent = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (task.isDone) Color.Green else Color.Gray
                            )
                        }
                    )
                }
            }
        }
    }
}
// PREVIEW ---

@Preview(showSystemUi = true, name = "Full Scheduler Interactive Preview")
@Composable
fun SchedulerPreview() {
    val mockTasks = remember {
        mutableStateListOf(
            TodoTask(
                id = 1,
                title = "Send Q1 Project Report",
                isDone = false,
                priority = Priority.HIGH,
                category = "Work",
                isPinned = true,
                dueDate = System.currentTimeMillis() + 3600000 // 1 hour from now
            ),
            TodoTask(
                id = 2,
                title = "Buy Groceries",
                isDone = false,
                priority = Priority.LOW,
                category = "Personal",
                isPinned = false
            ),
            TodoTask(
                id = 3,
                title = "Afternoon Yoga",
                isDone = true,
                priority = Priority.MEDIUM,
                category = "Health",
                isPinned = false,
                dueDate = System.currentTimeMillis() - 86400000 // Yesterday
            )
        )
    }
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SchedulerScreen(
                taskList = mockTasks,
                onAdd = { title, date, priority, category ->
                    val newId = (mockTasks.maxOfOrNull { it.id } ?: 0) + 1
                    mockTasks.add(
                        TodoTask(
                            id = newId,
                            title = title,
                            dueDate = date,
                            priority = priority,
                            category = category
                        )
                    )
                },
                onToggle = { task ->
                    val index = mockTasks.indexOfFirst { it.id == task.id }
                    if (index != -1) {
                        mockTasks[index] = task.copy(isDone = !task.isDone)
                    }
                },
                onTogglePin = { task ->
                    val index = mockTasks.indexOfFirst { it.id == task.id }
                    if (index != -1) {
                        mockTasks[index] = task.copy(isPinned = !task.isPinned)
                    }
                },
                onDelete = { task ->
                    mockTasks.removeIf { it.id == task.id }
                },
                onClearDone = {
                    mockTasks.removeIf { it.isDone }
                },
                onClearAll = {
                    mockTasks.clear()
                },
                onUndo = {
                }
            )
        }
    }
}
// MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: TaskViewModel = viewModel(factory = TaskViewModel.Factory(application))
            val tasks by vm.tasks.collectAsState(initial = emptyList())
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SchedulerScreen(
                        taskList = tasks,
                        onAdd = vm::addTask,
                        onToggle = vm::toggleTask,
                        onTogglePin = vm::togglePin,
                        onDelete = vm::deleteTask,
                        onClearDone = vm::deleteCompleted,
                        onClearAll = vm::clearAll,
                        onUndo = vm::undoDelete
                    )
                }
            }
        }
    }

}
