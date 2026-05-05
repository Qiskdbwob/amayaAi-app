package com.amaya.intelligence.ui.screens.persona.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.PersonaRepository
import com.amaya.intelligence.data.repository.SimplePersona
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.persona.shared.SimplePersonaEditor
import com.amaya.intelligence.ui.theme.LocalAmayaGradients
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPersonaScreen(
    onNavigateBack: () -> Unit,
    personaRepository: PersonaRepository
) {
    val scope = rememberCoroutineScope()
    val gradients = LocalAmayaGradients.current
    val snackbarHostState = remember { SnackbarHostState() }

    val personaState by produceState(initialValue = SimplePersona()) {
        value = personaRepository.getSimplePersona()
    }
    var persona by remember(personaState) { mutableStateOf(personaState) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.statusBarsPadding().height(52.dp))

                Text(
                    "Controls how Amaya speaks and behaves. Memory, skills, and context are managed separately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                SimplePersonaEditor(
                    personaRepository = personaRepository,
                    persona = persona,
                    onPersonaChange = { persona = it },
                    onSaved = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Persona saved successfully")
                        }
                    }
                )

                Spacer(modifier = Modifier.height(100.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.TopCenter)
                    .background(gradients.topScrim)
            )

            TopAppBar(
                title = { 
                    Text(
                        "Persona", 
                        style = MaterialTheme.typography.titleLarge, 
                        modifier = Modifier.padding(start = 12.dp)
                    ) 
                },
                navigationIcon = {
                    SettingsBackButton(onClick = onNavigateBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )
        }
    }
}
