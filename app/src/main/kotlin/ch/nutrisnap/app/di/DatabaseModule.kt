package ch.nutrisnap.app.di

import android.content.Context
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.DiaryDao
import ch.nutrisnap.app.data.db.FoodItemDao
import ch.nutrisnap.app.data.db.RecipeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt-Fundament: liefert die Room-DB und zentrale DAOs.
 * ViewModels werden schrittweise umgestellt; bestehende
 * AndroidViewModel-Pfade bleiben vorerst gueltig.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NutriDatabase =
        NutriDatabase.getInstance(context)

    @Provides
    fun provideFoodItemDao(db: NutriDatabase): FoodItemDao = db.foodItemDao()

    @Provides
    fun provideDiaryDao(db: NutriDatabase): DiaryDao = db.diaryDao()

    @Provides
    fun provideRecipeDao(db: NutriDatabase): RecipeDao = db.recipeDao()
}
