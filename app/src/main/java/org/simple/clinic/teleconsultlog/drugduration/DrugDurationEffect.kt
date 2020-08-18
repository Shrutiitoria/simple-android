package org.simple.clinic.teleconsultlog.drugduration

sealed class DrugDurationEffect

data class SaveDrugDuration(val duration: Int) : DrugDurationEffect()

data class SetDrugDuration(val duration: String) : DrugDurationEffect()
