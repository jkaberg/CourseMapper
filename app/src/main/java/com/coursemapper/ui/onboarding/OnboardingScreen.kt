package com.coursemapper.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** First run onboarding: record, navigate and offline maps. Shown once. */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text  = "Welcome to CourseMapper",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text  = "Set up and navigate marker courses with offline-ready maps.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            OnboardingStep(
                icon        = Icons.Default.FiberManualRecord,
                title       = "Add your courses",
                description = "Import the event's GPX files, or drive the course to " +
                    "capture a GPS track. Several routes can be combined into one " +
                    "event and placed in a single pass."
            )
            OnboardingStep(
                icon        = Icons.Default.Navigation,
                title       = "Drive it in one pass",
                description = "CourseMapper guides you to each placement stop and " +
                    "marks it done when you stop there — hands stay on the bars."
            )
            OnboardingStep(
                icon        = Icons.Default.WifiOff,
                title       = "Offline Maps Ready",
                description = "Map tiles are downloaded automatically before each " +
                    "event so the app works without signal in the field."
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick  = {
                    viewModel.completeOnboarding()
                    onFinish()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Get started")
            }
        }
    }
}

@Composable
private fun OnboardingStep(icon: ImageVector, title: String, description: String) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape  = MaterialTheme.shapes.small,
            color  = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier           = Modifier.size(24.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text  = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
