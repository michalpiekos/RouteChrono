# Structure and persistence

```text
Manual buttons / map endpoints → local endpoint pairs
                                       ↓
Android foreground GPS service → endpoint trip detector
                                       ↓
                               local journeys + GPS points
                                       ↓
                                  maps and summaries
```

The native Android app owns the map, recording controls, foreground location service and Room persistence. A small Kotlin/JVM core recognizes departures and arrivals in both directions (or, for a pair with a second end, between the start and either end) and calculates trace segments, distances and timing summaries. Recognition depends on endpoints, not on a predefined route. Multiple pairs have independent state.

Location and database mutations run serially. UI history reads use a separate executor. Every accepted recording point is persisted as it arrives; open recordings are marked interrupted after a process restart. Monotonic timestamps determine elapsed times, while wall-clock timestamps label recordings. There is no backend or cloud backup.

Room schema 3 stores endpoint pairs (with an optional second end), journeys (with the end they reached) and typed track points. The explicit 2→3 migration only adds nullable columns. Removing a pair archives its endpoints so existing journeys keep their context. The explicit 1→2 migration retains all six legacy tables untouched; their raw data stays on the device, without the former analysis/editor screens. Destructive migration fallback is not enabled.

The foreground service starts from visible app interaction, uses ordinary precise location permission, and exposes a persistent notification. It requests sticky restart, but a missing segment never becomes a completed trip. See Android's [foreground location guidance](https://developer.android.com/develop/sensors-and-location/location/permissions) and [service restart behavior](https://developer.android.com/reference/android/app/Service). Old activity/geofence pending intents are cancelled and their receivers are absent.

MapLibre displays OpenStreetMap raster tiles with attribution and an identified user agent. There is no prefetch or bulk tile download. Measurements remain local; tile requests go to the map provider.
