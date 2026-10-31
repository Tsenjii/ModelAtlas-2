package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.R

@Composable
internal fun KiloFactorySection(viewModel: AtlasViewModel) {
    val state by viewModel.kiloFactory.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadKiloFactoryDefaults() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.kilo_factory_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.kilo_factory_description), style = MaterialTheme.typography.bodySmall)
        if (state.problem) {
            Text(stringResource(R.string.kilo_factory_problem), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = viewModel::loadKiloFactoryDefaults, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.kilo_factory_reload)) }
        } else {
            Button(onClick = viewModel::addKiloFactoryDefaults, enabled = !state.loading && !state.installed, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (state.installed) R.string.kilo_factory_installed else R.string.kilo_factory_add))
            }
        }
        Text(stringResource(R.string.kilo_factory_notice), style = MaterialTheme.typography.bodySmall)
    }
}
