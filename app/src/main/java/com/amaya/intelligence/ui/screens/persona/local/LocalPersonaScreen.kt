package com.amaya.intelligence.ui.screens.persona.local

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.repository.PersonaRepository
import com.amaya.intelligence.data.repository.SimplePersona
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.persona.shared.SimplePersonaEditor
import kotlinx.coroutines.launch

private data class IosPersonaScreenColors(
    val groupedBackground: Color,
    val secondaryText: Color
)

@Composable
private fun iosPersonaScreenColors(): IosPersonaScreenColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosPersonaScreenColors(
            groupedBackground = Color(0xFF0B0B0F),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f)
        )
    } else {
        IosPersonaScreenColors(
            groupedBackground = Color(0xFFF2F2F7),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPersonaScreen(
    onNavigateBack: () -> Unit,
    personaRepository: PersonaRepository
) {
    val colors = iosPersonaScreenColors()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val personaState by produceState(initialValue = SimplePersona()) {
        value = personaRepository.getSimplePersona()
    }
    var persona by remember(personaState) { mutableStateOf(personaState) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(colors.groupedBackground)) {
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
                    color = colors.secondaryText,
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

            TopAppBar(
                title = { 
                    Text(
                        "Persona", 
                        style = MaterialTheme.typography.titleLarge, 
                        modifier = Modifier.padding(start = 12.dp),
                        fontWeight = FontWeight.SemiBold
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
