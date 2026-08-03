package com.coursemapper.data.db

import androidx.room.TypeConverter
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackStatus

/**
 * Offline enums stored by name. DAO queries compare against string literals,
 * and ordinals would change meaning if an enum value was inserted.
 */
class OfflineConverters {

    @TypeConverter
    fun statusToString(value: OfflinePackStatus?): String? = value?.name

    @TypeConverter
    fun statusFromString(value: String?): OfflinePackStatus? =
        value?.let { name -> OfflinePackStatus.entries.firstOrNull { it.name == name } }

    @TypeConverter
    fun reasonToString(value: OfflineFailureReason?): String? = value?.name

    @TypeConverter
    fun reasonFromString(value: String?): OfflineFailureReason? =
        value?.let { name -> OfflineFailureReason.entries.firstOrNull { it.name == name } }

    @TypeConverter
    fun choiceToString(value: OfflinePackChoice?): String? = value?.name

    @TypeConverter
    fun choiceFromString(value: String?): OfflinePackChoice? =
        value?.let { name -> OfflinePackChoice.entries.firstOrNull { it.name == name } }
}
