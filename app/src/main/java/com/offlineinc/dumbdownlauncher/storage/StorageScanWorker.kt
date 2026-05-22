package com.offlineinc.dumbdownlauncher.storage

/*
 * Intentionally empty.
 *
 * This file used to define `StorageScanWorker` (a nightly WorkManager
 * job) and `StorageSnapshotCache` (a SharedPreferences-backed snapshot
 * of `StorageCleanupOps.allSizesBytes()`) so the Free Up Space screen
 * could render instantly on open instead of paying the multi-second
 * `su` + `nsenter` startup cost.
 *
 * Removed because the 24-hour cache freshness window made the screen
 * show stale data whenever the user had just downloaded something
 * (Spotify songs, photos, etc.) and wanted to free space NOW —
 * exactly the moment they're opening Free Up Space. Live scan on
 * every open is the simpler, correct behaviour.
 *
 * Kept as an empty file because the sandbox this branch was developed
 * in couldn't delete files; a future maintenance pass can `git rm`
 * it outright.
 */
