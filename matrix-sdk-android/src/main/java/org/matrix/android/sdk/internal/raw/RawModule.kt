/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.matrix.android.sdk.internal.raw

import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import io.realm.kotlin.RealmConfiguration
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.internal.database.RealmInstance
import org.matrix.android.sdk.internal.database.RealmKeysUtils
import org.matrix.android.sdk.internal.di.GlobalDatabase
import org.matrix.android.sdk.internal.di.MatrixCoroutineScope
import org.matrix.android.sdk.internal.di.MatrixScope
import org.matrix.android.sdk.internal.di.Unauthenticated
import org.matrix.android.sdk.internal.network.RetrofitFactory
import org.matrix.android.sdk.internal.raw.db.migration.GlobalRealmMigration

@Module
internal abstract class RawModule {

    @Module
    companion object {
        private const val DB_ALIAS = "matrix-sdk-global"

        @JvmStatic
        @Provides
        @GlobalDatabase
        fun providesRealmConfiguration(
                realmKeysUtils: RealmKeysUtils,
                globalRealmMigration: GlobalRealmMigration
        ): RealmConfiguration {
            return RealmConfiguration.Builder(GLOBAL_REALM_SCHEMA)
                    .apply {
                        realmKeysUtils.configureEncryption(this, DB_ALIAS)
                    }
                    .name("matrix-sdk-global.realm")
                    .schemaVersion(globalRealmMigration.schemaVersion)
                    .migration(globalRealmMigration)
                    .build()
        }

        @JvmStatic
        @Provides
        @GlobalDatabase
        @MatrixScope
        fun providesRealmInstance(
                @GlobalDatabase realmConfiguration: RealmConfiguration,
                @MatrixCoroutineScope matrixCoroutineScope: CoroutineScope,
                matrixCoroutineDispatchers: MatrixCoroutineDispatchers
        ): RealmInstance {
            return RealmInstance(
                    coroutineScope = matrixCoroutineScope,
                    realmConfiguration = realmConfiguration,
                    coroutineDispatcher = matrixCoroutineDispatchers.io
            )
        }

        @Provides
        @JvmStatic
        fun providesRawAPI(
                @Unauthenticated okHttpClient: Lazy<OkHttpClient>,
                retrofitFactory: RetrofitFactory
        ): RawAPI {
            return retrofitFactory.create(okHttpClient, "https://example.org").create(RawAPI::class.java)
        }
    }

    @Binds
    abstract fun bindRawService(service: DefaultRawService): RawService

    @Binds
    abstract fun bindGetUrlTask(task: DefaultGetUrlTask): GetUrlTask

    @Binds
    abstract fun bindCleanRawCacheTask(task: DefaultCleanRawCacheTask): CleanRawCacheTask
}
