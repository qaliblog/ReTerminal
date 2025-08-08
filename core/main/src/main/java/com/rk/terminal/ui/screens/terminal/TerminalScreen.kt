package com.rk.terminal.ui.screens.terminal

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.android.material.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.dpToPx
import com.rk.libcommons.pendingCommand
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.components.SettingsToggle
import com.rk.terminal.ui.components.ScrollableTabLayout
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.settings.SettingsCard
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysConstants
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysInfo
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.rk.terminal.ui.theme.KarbonTheme
import com.termux.view.TerminalView
import io.github.rosemoe.sora.widget.CodeEditor
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateListOf

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)


var darkText = mutableStateOf(Settings.blackTextColor)
var bitmap = mutableStateOf<ImageBitmap?>(null)

private val file = application!!.filesDir.child("font.ttf")
private var font = (if (file.exists() && file.canRead()){
    Typeface.createFromFile(file)
}else{
    Typeface.MONOSPACE
})

suspend fun setFont(typeface: Typeface) = withContext(Dispatchers.Main){
    font = typeface
    terminalView.get()?.apply {
        setTypeface(typeface)
        onScreenUpdated()
    }
}

inline fun getViewColor(): Int{
    return if (darkText.value){
        Color.BLACK
    }else{
        Color.WHITE
    }
}

inline fun getComposeColor():androidx.compose.ui.graphics.Color{
    return if (darkText.value){
        androidx.compose.ui.graphics.Color.Black
    }else{
        androidx.compose.ui.graphics.Color.White
    }
}

var showToolbar = mutableStateOf(Settings.toolbar)
var showVirtualKeys = mutableStateOf(Settings.virtualKeys)
var showHorizontalToolbar = mutableStateOf(Settings.toolbar)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    mainActivityActivity: MainActivity,
    navController: NavController
) {
    val context = LocalContext.current
    val isDarkMode = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()


    LaunchedEffect(Unit){
        withContext(Dispatchers.IO){
            if (context.filesDir.child("background").exists().not()){
                darkText.value = !isDarkMode
            }else if (bitmap.value == null){
                val fullBitmap = BitmapFactory.decodeFile(context.filesDir.child("background").absolutePath)?.asImageBitmap()
                if (fullBitmap != null) bitmap.value = fullBitmap
            }
        }


        scope.launch(Dispatchers.Main){
            virtualKeysView.get()?.apply {
                virtualKeysViewClient =
                    terminalView.get()?.mTermSession?.let {
                        VirtualKeysListener(
                            it
                        )
                    }

                buttonTextColor = getViewColor()


                reload(
                    VirtualKeysInfo(
                        VIRTUAL_KEYS,
                        "",
                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                    )
                )
            }

            terminalView.get()?.apply {
                onScreenUpdated()

                mEmulator?.mColors?.mCurrentColors?.apply {
                    set(256, getViewColor())
                    set(258, getViewColor())
                }
            }
        }


    }

    Box {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp
        val drawerWidth = (screenWidthDp * 0.84).dp
        var showAddDialog by remember { mutableStateOf(false) }

        BackHandler(enabled = drawerState.isOpen) {
            scope.launch {
                drawerState.close()
            }
        }

        if (drawerState.isClosed){
            SetStatusBarTextColor(isDarkIcons = darkText.value)
        }else{
            SetStatusBarTextColor(isDarkIcons = !isDarkMode)
        }

        if (showAddDialog){
            AlertDialog(onDismissRequest = { showAddDialog = false }, confirmButton = {}, title = {
                Text(text = stringResource(strings.add_new_session))
            }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    fun createSession(workingMode: Int){
                        fun generateUniqueString(existingStrings: List<String>): String {
                            var index = 1
                            var newString: String
                            do {
                                newString = "main$index"
                                index++
                            } while (newString in existingStrings)
                            return newString
                        }
                        val existing = mainActivityActivity.sessionBinder!!.getService().sessionList.keys.toList()
                        val sessionId = generateUniqueString(existing)
                        terminalView.get()?.let { view ->
                            val client = TerminalBackEnd(view, mainActivityActivity)
                            mainActivityActivity.sessionBinder!!.createSession(sessionId, client, mainActivityActivity, workingMode)
                        }
                        changeSession(mainActivityActivity, session_id = sessionId)
                    }
                    SettingsCard(title = { Text("SSH Session") }, description = { Text("Connect to a remote host") }, onClick = {})
                    // SSH form
                    var sshHost by remember { mutableStateOf("") }
                    var sshPort by remember { mutableStateOf("22") }
                    var sshUser by remember { mutableStateOf("") }
                    var sshPassword by remember { mutableStateOf("") }
                    var sshIdentityPath by remember { mutableStateOf("") }
                    var sshUsePassword by remember { mutableStateOf(true) }
                    var sshSaveProfile by remember { mutableStateOf(true) }
                    var sshProfileName by remember { mutableStateOf("") }
 
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = sshHost, onValueChange = { sshHost = it }, label = { Text("Host or IP") })
                        OutlinedTextField(value = sshPort, onValueChange = { sshPort = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Port") })
                        OutlinedTextField(value = sshUser, onValueChange = { sshUser = it }, label = { Text("Username") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = sshUsePassword, onClick = { sshUsePassword = true }, label = { Text("Password") })
                            FilterChip(selected = !sshUsePassword, onClick = { sshUsePassword = false }, label = { Text("Private Key") })
                        }
                        if (sshUsePassword) {
                            OutlinedTextField(value = sshPassword, onValueChange = { sshPassword = it }, label = { Text("Password") })
                        } else {
                            OutlinedTextField(value = sshIdentityPath, onValueChange = { sshIdentityPath = it }, label = { Text("Identity File Path") })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sshSaveProfile, onCheckedChange = { sshSaveProfile = it })
                            Text("Save profile")
                        }
                        if (sshSaveProfile) {
                            OutlinedTextField(value = sshProfileName, onValueChange = { sshProfileName = it }, label = { Text("Profile name") })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ElevatedButton(onClick = {
                                // Save profile
                                val profileId = (System.currentTimeMillis()).toString()
                                val profile = JSONObject().apply {
                                    put("id", profileId)
                                    put("name", if (sshProfileName.isNotBlank()) sshProfileName else "$sshUser@$sshHost:$sshPort")
                                    put("host", sshHost)
                                    put("port", sshPort.toIntOrNull() ?: 22)
                                    put("user", sshUser)
                                    put("usePassword", sshUsePassword)
                                    if (sshUsePassword) put("password", sshPassword) else put("identityPath", sshIdentityPath)
                                }
                                val arr = kotlin.runCatching { JSONArray(Settings.ssh_profiles) }.getOrElse { JSONArray() }
                                arr.put(profile)
                                Settings.ssh_profiles = arr.toString()
                                Settings.ssh_last_profile_id = profileId
                            }, enabled = sshHost.isNotBlank() && (sshUsePassword && sshPassword.isNotBlank() || (!sshUsePassword && sshIdentityPath.isNotBlank()))) { Text("Save") }
                            Button(onClick = {
                                // External ssh fallback until native session is finished
                                val port = sshPort.toIntOrNull() ?: 22
                                val identityPart = if (sshUsePassword) "" else "-i \"$sshIdentityPath\" "
                                val userPart = if (sshUser.isNotBlank()) "$sshUser@" else ""
                                val cmd = "if ! command -v ssh >/dev/null 2>&1; then echo 'Error: ssh client not found in PATH. Please install an ssh client.'; exit 127; fi; ssh -p $port ${identityPart}${userPart}$sshHost"
                                pendingCommand = com.rk.libcommons.TerminalCommand(
                                    alpine = false,
                                    shell = "/system/bin/sh",
                                    args = arrayOf("-c", cmd),
                                    id = "ssh-${'$'}{System.currentTimeMillis()}",
                                    workingMode = WorkingMode.SSH,
                                    terminatePreviousSession = false,
                                    workingDir = "/sdcard",
                                    env = arrayOf()
                                )
                                createSession(workingMode = WorkingMode.SSH)
                                showAddDialog = false
                            }, enabled = sshHost.isNotBlank()) { Text("Connect") }
                        }
                    }
                }
            })
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen || !(showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)),
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Session",
                                style = MaterialTheme.typography.titleLarge
                            )

                            Row {
                                IconButton(onClick = {
                                    navController.navigate(MainActivityRoutes.Settings.route)
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = null
                                    )
                                }

                                IconButton(onClick = {
                                    showAddDialog = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null
                                    )
                                }

                            }


                        }

                        mainActivityActivity.sessionBinder?.getService()?.sessionList?.keys?.toList()?.let {
                            LazyColumn {
                                items(it) { session_id ->
                                    SelectableCard(
                                        selected = session_id == mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first,
                                        onSelect = {
                                            changeSession(
                                                mainActivityActivity,
                                                session_id
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = session_id,
                                                style = MaterialTheme.typography.bodyLarge
                                            )

                                            if (session_id != mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first) {
                                                Spacer(modifier = Modifier.weight(1f))

                                                IconButton(
                                                    onClick = {
                                                        println(session_id)
                                                        mainActivityActivity.sessionBinder?.terminateSession(
                                                            session_id
                                                        )
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    
                                                    Icon(
                                                        imageVector = Icons.Outlined.Delete,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                        }
                                    }
                                }
                            }
                        }

                    }
                }

            },
            content = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BackgroundImage()
                        val color = getComposeColor()
                        Column {

                            fun getNameOfWorkingMode(workingMode:Int?):String{
                                return when(workingMode){
                                    0 -> "ALPINE".lowercase()
                                    1 -> "ANDROID".lowercase()
                                    2 -> "SSH".lowercase()
                                    null -> "null"
                                    else -> "unknown"
                                }
                            }


                            if (showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)){
                                TopAppBar(
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                                    ),
                                    title = {
                                        Column {
                                            Text(text = "ReTerminal",color = color)
                                            Text(style = MaterialTheme.typography.bodySmall,text = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first + " (${getNameOfWorkingMode(mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.second)})",color = color)
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            scope.launch { drawerState.open() }
                                        }) {
                                            Icon(Icons.Default.Menu, null, tint = color)
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            showAddDialog = true
                                        }) {
                                            Icon(Icons.Default.Add,null, tint = color)
                                        }
                                    }
                                )
                            }

                            val density = LocalDensity.current
                            Column(modifier = Modifier.imePadding().navigationBarsPadding().padding(top = if (showToolbar.value){0.dp}else{
                                with(density){
                                    TopAppBarDefaults.windowInsets.getTop(density).toDp()
                                }
                            })) {
                                AndroidView(
                                    factory = { context ->
                                        TerminalView(context, null).apply {
                                            terminalView = WeakReference(this)
                                            setTextSize(
                                                dpToPx(
                                                    Settings.terminal_font_size.toFloat(),
                                                    context
                                                )
                                            )
                                            val client = TerminalBackEnd(this, mainActivityActivity)

                                            val session = if (pendingCommand != null) {
                                                mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(
                                                    pendingCommand!!.id, pendingCommand!!.workingMode)
                                                mainActivityActivity.sessionBinder!!.getSession(
                                                    pendingCommand!!.id
                                                )
                                                    ?: mainActivityActivity.sessionBinder!!.createSession(
                                                        pendingCommand!!.id,
                                                        client,
                                                        mainActivityActivity, workingMode = Settings.working_Mode
                                                    )
                                            } else {
                                                mainActivityActivity.sessionBinder!!.getSession(
                                                    mainActivityActivity.sessionBinder!!.getService().currentSession.value.first
                                                )
                                                    ?: mainActivityActivity.sessionBinder!!.createSession(
                                                        mainActivityActivity.sessionBinder!!.getService().currentSession.value.first,
                                                        client,
                                                        mainActivityActivity,workingMode = Settings.working_Mode
                                                    )
                                            }

                                            session.updateTerminalSessionClient(client)
                                            attachSession(session)
                                            setTerminalViewClient(client)
                                            setTypeface(font)

                                            post {
                                                val color = getViewColor()

                                                keepScreenOn = true
                                                requestFocus()
                                                isFocusableInTouchMode = true

                                                mEmulator?.mColors?.mCurrentColors?.apply {
                                                    set(256, color)
                                                    set(258, color)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    update = { terminalView ->
                                        terminalView.onScreenUpdated()
                                       val color = getViewColor()

                                        terminalView.mEmulator?.mColors?.mCurrentColors?.apply {
                                            set(256, color)
                                            set(258, color)
                                        }
                                    },
                                )

                                if (showVirtualKeys.value){
                                    AndroidView(update = {
                                        it.apply {
                                            virtualKeysViewClient =
                                                terminalView.get()?.mTermSession?.let {
                                                    VirtualKeysListener(
                                                        it
                                                    )
                                                }


                                            buttonTextColor = getViewColor()


                                            reload(
                                                VirtualKeysInfo(
                                                    VIRTUAL_KEYS,
                                                    "",
                                                    VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                                )
                                            )
                                        }
                                    },
                                        factory = { context ->
                                            VirtualKeysView(context, null).apply {
                                                virtualKeysView = WeakReference(this)

                                                virtualKeysViewClient =
                                                    terminalView.get()?.mTermSession?.let {
                                                        VirtualKeysListener(
                                                            it
                                                        )
                                                    }


                                                buttonTextColor = getViewColor()


                                                reload(
                                                    VirtualKeysInfo(
                                                        VIRTUAL_KEYS,
                                                        "",
                                                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(75.dp)
                                    )
                                }else{
                                    virtualKeysView = WeakReference(null)
                                }

                            }
                            // Tabs: Terminal | Files | Editor
                            val tabs = remember { mutableStateListOf("Terminal","Files","Editor") }
                            val pagerState = rememberPagerState(pageCount = { tabs.size })
                            val selectedFileForEditor = remember { mutableStateOf<java.io.File?>(null) }
                            ScrollableTabLayout(modifier = Modifier.fillMaxWidth(), tabs = tabs, pagerState = pagerState) { tabIndex ->
                                when (tabIndex) {
                                    0 -> {
                                        Column(modifier = Modifier.imePadding().navigationBarsPadding().padding(top = if (showToolbar.value){0.dp}else{
                                with(density){
                                    TopAppBarDefaults.windowInsets.getTop(density).toDp()
                                }
                            })) {
                                AndroidView(
                                    factory = { context ->
                                        TerminalView(context, null).apply {
                                            terminalView = WeakReference(this)
                                            setTextSize(
                                                dpToPx(
                                                    Settings.terminal_font_size.toFloat(),
                                                    context
                                                )
                                            )
                                            val client = TerminalBackEnd(this, mainActivityActivity)

                                            val session = if (pendingCommand != null) {
                                                mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(
                                                    pendingCommand!!.id, pendingCommand!!.workingMode)
                                                mainActivityActivity.sessionBinder!!.getSession(
                                                    pendingCommand!!.id
                                                )
                                                    ?: mainActivityActivity.sessionBinder!!.createSession(
                                                        pendingCommand!!.id,
                                                        client,
                                                        mainActivityActivity, workingMode = Settings.working_Mode
                                                    )
                                            } else {
                                                mainActivityActivity.sessionBinder!!.getSession(
                                                    mainActivityActivity.sessionBinder!!.getService().currentSession.value.first
                                                )
                                                    ?: mainActivityActivity.sessionBinder!!.createSession(
                                                        mainActivityActivity.sessionBinder!!.getService().currentSession.value.first,
                                                        client,
                                                        mainActivityActivity,workingMode = Settings.working_Mode
                                                    )
                                            }

                                            session.updateTerminalSessionClient(client)
                                            attachSession(session)
                                            setTerminalViewClient(client)
                                            setTypeface(font)

                                            post {
                                                val color = getViewColor()

                                                keepScreenOn = true
                                                requestFocus()
                                                isFocusableInTouchMode = true

                                                mEmulator?.mColors?.mCurrentColors?.apply {
                                                    set(256, color)
                                                    set(258, color)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    update = { terminalView ->
                                        terminalView.onScreenUpdated()
                                       val color = getViewColor()

                                        terminalView.mEmulator?.mColors?.mCurrentColors?.apply {
                                            set(256, color)
                                            set(258, color)
                                        }
                                    },
                                )

                                if (showVirtualKeys.value){
                                    AndroidView(update = {
                                        it.apply {
                                            virtualKeysViewClient =
                                                terminalView.get()?.mTermSession?.let {
                                                    VirtualKeysListener(
                                                        it
                                                    )
                                                }


                                            buttonTextColor = getViewColor()


                                            reload(
                                                VirtualKeysInfo(
                                                    VIRTUAL_KEYS,
                                                    "",
                                                    VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                                )
                                            )
                                        }
                                    },
                                        factory = { context ->
                                            VirtualKeysView(context, null).apply {
                                                virtualKeysView = WeakReference(this)

                                                virtualKeysViewClient =
                                                    terminalView.get()?.mTermSession?.let {
                                                        VirtualKeysListener(
                                                            it
                                                        )
                                                    }


                                                buttonTextColor = getViewColor()


                                                reload(
                                                    VirtualKeysInfo(
                                                        VIRTUAL_KEYS,
                                                        "",
                                                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(75.dp)
                                    )
                                }else{
                                    virtualKeysView = WeakReference(null)
                                }

                            }
                                    }
                                    1 -> {
                                        // Simple File Manager: list /sdcard
                                        val ctx = LocalContext.current
                                        val dir = remember { mutableStateOf(File("/sdcard")) }
                                        val files = remember(dir.value) { dir.value.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList() }
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Button(onClick = {
                                                    val newDir = File(dir.value, "NewFolder")
                                                    var candidate = newDir
                                                    var i = 1
                                                    while (candidate.exists()) { candidate = File(dir.value, "NewFolder($i)"); i++ }
                                                    candidate.mkdirs()
                                                }) { Text("New Folder") }
                                                Spacer(Modifier.width(8.dp))
                                                Button(onClick = {
                                                    val newFile = File(dir.value, "NewFile.txt")
                                                    var candidate = newFile
                                                    var i = 1
                                                    while (candidate.exists()) { candidate = File(dir.value, "NewFile($i).txt"); i++ }
                                                    candidate.createNewFile()
                                                }) { Text("New File") }
                                                Spacer(Modifier.weight(1f))
                                                if (dir.value.parentFile != null) {
                                                    Button(onClick = { dir.value = dir.value.parentFile!! }) { Text("Up") }
                                                }
                                            }
                                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                                items(files.size) { idx ->
                                                    val f = files[idx]
                                                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Text(f.name, modifier = Modifier.weight(1f))
                                                        if (f.isDirectory) {
                                                            Button(onClick = { dir.value = f }) { Text("Open") }
                                                        } else {
                                                            Button(onClick = {
                                                                // Open in editor tab
                                                                selectedFileForEditor.value = f
                                                                // switch to Editor tab
                                                                scope.launch { pagerState.scrollToPage(2) }
                                                            }) { Text("Edit") }
                                                            Spacer(Modifier.width(8.dp))
                                                            Button(onClick = {
                                                                // Rename inline: append .renamed if exists
                                                                val renamed = File(f.parentFile, f.name + ".renamed")
                                                                if (!renamed.exists()) {
                                                                    runCatching { f.renameTo(renamed) }
                                                                }
                                                                dir.value = dir.value
                                                            }) { Text("Rename") }
                                                            Spacer(Modifier.width(8.dp))
                                                            Button(onClick = {
                                                                // Copy to parent with (copy) suffix
                                                                val parent = f.parentFile ?: dir.value
                                                                val target = File(parent, f.nameWithoutExtension + " (copy)" + if (f.extension.isNotEmpty()) ".${f.extension}" else "")
                                                                runCatching {
                                                                    if (f.isFile) {
                                                                        f.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                                                                    }
                                                                }.onSuccess { dir.value = dir.value }
                                                            }) { Text("Copy") }
                                                            Spacer(Modifier.width(8.dp))
                                                            Button(onClick = {
                                                                // Move to parent directory
                                                                val parent = f.parentFile ?: dir.value
                                                                val target = File(parent, f.name)
                                                                if (!target.exists()) {
                                                                    runCatching { f.renameTo(target) }.onSuccess { dir.value = dir.value }
                                                                }
                                                            }) { Text("Move") }
                                                            Spacer(Modifier.width(8.dp))
                                                            Button(onClick = { runCatching { f.delete() }.onSuccess { /* refresh */ dir.value = dir.value } }) { Text("Delete") }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    2 -> {
                                        // Text editor using Sora CodeEditor
                                        val file = selectedFileForEditor.value
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            var editorRef: CodeEditor? = null
                                            var initialText by remember { mutableStateOf("") }
                                            val isDirty = remember(file, initialText) { mutableStateOf(false) }
                                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(((if (isDirty.value) "* " else "") + (file?.absolutePath ?: "Untitled")), modifier = Modifier.weight(1f))
                                                Spacer(Modifier.width(8.dp))
                                                Button(onClick = {
                                                    if (file != null) {
                                                        val txt = editorRef?.text.toString()
                                                        runCatching { file.writeText(txt) }
                                                        initialText = txt
                                                        isDirty.value = false
                                                    }
                                                }, enabled = file != null) { Text("Save") }
                                                Spacer(Modifier.width(8.dp))
                                                Button(onClick = {
                                                    // Save As: write to sibling file with (copy).txt
                                                    val base = file?.name ?: "Untitled.txt"
                                                    val targetParent = file?.parentFile ?: File("/sdcard")
                                                    val target = File(targetParent, base.removeSuffix(".txt") + " (copy).txt")
                                                    val txt = editorRef?.text.toString()
                                                    runCatching { target.writeText(txt) }
                                                    selectedFileForEditor.value = target
                                                    initialText = txt
                                                    isDirty.value = false
                                                }) { Text("Save As") }
                                            }
                                            AndroidView(factory = { ctx ->
                                                CodeEditor(ctx).apply {
                                                    editorRef = this
                                                    val content = if (file != null && file.exists()) file.readText() else ""
                                                    setText(content)
                                                    initialText = content
                                                    setOnTextChangeListener { _, _ ->
                                                        isDirty.value = (editorRef?.text.toString() != initialText)
                                                    }
                                                }
                                            }, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }



                }

            })
    }
}

@Composable
fun BackgroundImage() {
    bitmap.value?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-1f)
        )
    }
}

@Composable
fun SetStatusBarTextColor(isDarkIcons: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return

    SideEffect {
        WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = isDarkIcons
    }
}



@Composable
fun SelectableCard(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "containerColor"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 2.dp
        ),
        enabled = enabled,
        onClick = onSelect
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            content()
        }
    }
}


fun changeSession(mainActivityActivity: MainActivity, session_id: String) {
    terminalView.get()?.apply {
        val client = TerminalBackEnd(this, mainActivityActivity)
        val session =
            mainActivityActivity.sessionBinder!!.getSession(session_id)
                ?: mainActivityActivity.sessionBinder!!.createSession(
                    session_id,
                    client,
                    mainActivityActivity,workingMode = Settings.working_Mode
                )
        session.updateTerminalSessionClient(client)
        attachSession(session)
        setTerminalViewClient(client)
        post {
            val typedValue = TypedValue()

            context.theme.resolveAttribute(
                R.attr.colorOnSurface,
                typedValue,
                true
            )
            keepScreenOn = true
            requestFocus()
            isFocusableInTouchMode = true

            mEmulator?.mColors?.mCurrentColors?.apply {
                set(256, typedValue.data)
                set(258, typedValue.data)
            }
        }
        virtualKeysView.get()?.apply {
            virtualKeysViewClient =
                terminalView.get()?.mTermSession?.let { VirtualKeysListener(it) }
        }

    }
    mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(session_id,mainActivityActivity.sessionBinder!!.getService().sessionList[session_id]!!)

}


const val VIRTUAL_KEYS =
    ("[" + "\n  [" + "\n    \"ESC\"," + "\n    {" + "\n      \"key\": \"/\"," + "\n      \"popup\": \"\\\\\"" + "\n    }," + "\n    {" + "\n      \"key\": \"-\"," + "\n      \"popup\": \"|\"" + "\n    }," + "\n    \"HOME\"," + "\n    \"UP\"," + "\n    \"END\"," + "\n    \"PGUP\"" + "\n  ]," + "\n  [" + "\n    \"TAB\"," + "\n    \"CTRL\"," + "\n    \"ALT\"," + "\n    \"LEFT\"," + "\n    \"DOWN\"," + "\n    \"RIGHT\"," + "\n    \"PGDN\"" + "\n  ]" + "\n]")

// SSHJ native integration will be added in a dedicated session type next step