package com.example.llama.agent.irismotion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llama.SettingsManager
import com.example.llama.agent.irismotion.PhysicalGesture
import com.example.llama.agent.irismotion.database.RemoteProfileEntity

class RemoteProfilesActivity : ComponentActivity() {

    private val viewModel: RemoteProfilesViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val profiles by viewModel.profiles.collectAsState()
                val activeProfileId by viewModel.activeProfileId.collectAsState()

                // State pro dialog úpravy
                var editingProfile by remember { mutableStateOf<RemoteProfileEntity?>(null) }
                val settingsManager = remember { SettingsManager(this@RemoteProfilesActivity) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Virtual HW Profiles") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    // Příklad využití SettingsManageru: přepnutí Remote Mode
                                    val isRemote = settingsManager.isRemoteModeEnabled
                                    settingsManager.isRemoteModeEnabled = !isRemote
                                }) {
                                    Icon(Icons.Default.Build, contentDescription = "Přepnout Remote Mode", tint = if (settingsManager.isRemoteModeEnabled) Color.Green else Color.Gray)
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(Color(0xFF121212))
                    ) {
                        if (profiles.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(profiles) { profile ->
                                    ProfileCard(
                                        profile = profile,
                                        isActive = profile.id == activeProfileId,
                                        onActivate = { viewModel.setActiveProfile(profile.id) },
                                        onEdit = { editingProfile = profile }
                                    )
                                }
                            }
                        }
                    }

                    // Dialog pro úpravu mapování gest
                    editingProfile?.let { profile ->
                        EditProfileDialog(
                            profile = profile,
                            onDismiss = { editingProfile = null },
                            onMappingChange = { actionId, targetAction ->
                                viewModel.updateProfileMapping(profile, actionId, targetAction)
                                // Lokálně updatneme objekt aby se dialog nezavřel a ukázal změnu
                                val newMap = profile.gestureMapping.toMutableMap()
                                newMap[actionId] = targetAction
                                editingProfile = profile.copy(gestureMapping = newMap)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileCard(
    profile: RemoteProfileEntity,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF1E3A8A) else Color(0xFF1E1E1E)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = profile.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Typ: ${profile.type}",
                        fontSize = 14.sp,
                        color = Color.LightGray
                    )
                }
                
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Active",
                        tint = Color.Green,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upravit Gesta")
                }

                if (!isActive) {
                    Button(onClick = onActivate) {
                        Text("Aktivovat")
                    }
                }
            }
        }
    }
}

@Composable
fun EditProfileDialog(
    profile: RemoteProfileEntity,
    onDismiss: () -> Unit,
    onMappingChange: (Int, Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mapování profilu: ${profile.name}") },
        text = {
            Column {
                Text("Zde můžete propojit oční gesta s fyzickými tlačítky myši/gamepadu.", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))

                // Výpis všech dostupných gest načtených z Enumu dynamicky
                PhysicalGesture.entries.forEach { gesture ->
                    val currentAction = profile.gestureMapping[gesture.id] ?: gesture.defaultActionId
                    GestureMappingRow(
                        gestureName = gesture.localizedName,
                        gestureId = gesture.id,
                        currentAction = currentAction,
                        onMappingChange = { newAction -> onMappingChange(gesture.id, newAction) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureMappingRow(
    gestureName: String,
    gestureId: Int,
    currentAction: Int,
    onMappingChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    // Názvy cílových akcí
    val actionNames = mapOf(
        0 to "Levé tlačítko",
        1 to "Pravé tlačítko",
        2 to "Prostřední tlačítko",
        -1 to "Kolečko dolů",
        -2 to "Kolečko nahoru",
        -99 to "Nic (Ignorovat)"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$gestureName (ID: $gestureId)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionNames[currentAction] ?: "Neznámá akce")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                actionNames.forEach { (actionId, actionName) ->
                    DropdownMenuItem(
                        text = { Text(actionName) },
                        onClick = {
                            onMappingChange(actionId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
