package ch.nutrisnap.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * FTS-Index über [FoodItem] (name + brand).
 * contentEntity = Room hält den Index an food_items gebunden;
 * Migration 33→34 legt Tabelle + Trigger an und rebuildet den Index.
 */
@Fts4(contentEntity = FoodItem::class)
@Entity(tableName = "food_items_fts")
data class FoodItemFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int,
    val name: String,
    val brand: String? = null
)
