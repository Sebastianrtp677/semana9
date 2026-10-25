package com.example.semana9;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muestra un mapa de OpenStreetMap (osmdroid, sin API key) con la ubicación actual del usuario,
 * permite añadir marcadores (pulsación larga o botón "Marcar aquí") y capturar/mostrar una imagen.
 *
 * Las tareas lentas (geocodificación inversa y guardado de la foto) se ejecutan en un
 * hilo secundario (ExecutorService) y el resultado se publica en el hilo principal.
 */
public class MainActivity extends AppCompatActivity {

    private static final double DEFAULT_ZOOM = 15.0;
    private static final GeoPoint CHILE_CENTER = new GeoPoint(-33.4489, -70.6693); // Santiago

    // Mapa de calles de Esri (sin API key). Esri usa el orden z/y/x en la URL.
    private static final OnlineTileSourceBase ESRI_STREETS = new OnlineTileSourceBase(
            "EsriWorldStreetMap", 0, 19, 256, ".png",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"},
            "Tiles © Esri") {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex) + "/"
                    + MapTileIndex.getY(pMapTileIndex) + "/"
                    + MapTileIndex.getX(pMapTileIndex);
        }
    };

    private MapView mapView;
    private MyLocationNewOverlay myLocationOverlay;
    private FusedLocationProviderClient fusedLocationClient;
    private ImageView photoPreview;

    // Hilo de trabajo para operaciones de E/S que no deben bloquear la UI.
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionsResult);

    // Captura una miniatura (thumbnail) con la app de cámara; no requiere permiso CAMERA.
    private final ActivityResultLauncher<Void> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicturePreview(),
                    this::onPictureTaken);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // osmdroid: identificarse con un user agent y guardar la caché en almacenamiento de la app.
        Context ctx = getApplicationContext();
        SharedPreferences prefs = getSharedPreferences("osmdroid", MODE_PRIVATE);
        Configuration.getInstance().load(ctx, prefs);
        // OpenStreetMap bloquea (403) los user agents genéricos como "com.example...".
        // Debe identificar a tu app de forma única; opcional: agrega un correo de contacto.
        Configuration.getInstance().setUserAgentValue("Semana9-GPSMapApp/1.0 (proyecto de clase Android)");
        File osmBase = new File(getCacheDir(), "osmdroid_v4");
        Configuration.getInstance().setOsmdroidBasePath(osmBase);
        Configuration.getInstance().setOsmdroidTileCache(new File(osmBase, "tiles"));

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        photoPreview = findViewById(R.id.photoPreview);
        mapView = findViewById(R.id.mapView);
        Button btnMyLocation = findViewById(R.id.btnMyLocation);
        Button btnAddMarker = findViewById(R.id.btnAddMarker);
        Button btnPhoto = findViewById(R.id.btnPhoto);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        setupMap();

        btnMyLocation.setOnClickListener(v -> moveToCurrentLocation(false));
        btnAddMarker.setOnClickListener(v -> moveToCurrentLocation(true));
        btnPhoto.setOnClickListener(v -> launchCamera());

        Toast.makeText(this, R.string.long_press_hint, Toast.LENGTH_LONG).show();
        checkLocationPermission();
    }

    private void setupMap() {
        mapView.setTileSource(ESRI_STREETS);
        mapView.getOverlays().add(new CopyrightOverlay(this));
        mapView.setMultiTouchControls(true);

        // Vista inicial en Chile mientras se obtiene la ubicación real del GPS.
        mapView.getController().setZoom(4.0);
        mapView.getController().setCenter(CHILE_CENTER);

        // Pulsación larga = nuevo marcador en ese punto.
        MapEventsOverlay eventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                addMarkerAt(p);
                return true;
            }
        });
        mapView.getOverlays().add(0, eventsOverlay);
    }

    // ---------------------------------------------------------------- Permisos

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkLocationPermission() {
        if (hasLocationPermission()) {
            enableMyLocationLayer();
            moveToCurrentLocation(false);
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        if (hasLocationPermission()) {
            enableMyLocationLayer();
            moveToCurrentLocation(false);
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dibuja el indicador de la ubicación actual sobre el mapa. */
    private void enableMyLocationLayer() {
        if (myLocationOverlay == null && hasLocationPermission()) {
            myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mapView);
            myLocationOverlay.enableMyLocation();
            mapView.getOverlays().add(myLocationOverlay);
            mapView.invalidate();
        }
    }

    // --------------------------------------------------------------- Ubicación

    /**
     * Obtiene la ubicación actual, centra el mapa y opcionalmente deja un marcador.
     */
    @SuppressWarnings("MissingPermission")
    private void moveToCurrentLocation(boolean addMarker) {
        if (!hasLocationPermission()) {
            checkLocationPermission();
            return;
        }
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location == null) {
                        Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show();
                        return;
                    }
                    GeoPoint here = new GeoPoint(location.getLatitude(), location.getLongitude());
                    mapView.getController().setZoom(DEFAULT_ZOOM);
                    mapView.getController().animateTo(here);
                    if (addMarker) {
                        addMarkerAt(here);
                    }
                })
                .addOnFailureListener(this, e ->
                        Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------- Marcadores

    private void addMarkerAt(@NonNull GeoPoint position) {
        Marker marker = new Marker(mapView);
        marker.setPosition(position);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(getString(R.string.marker_default_title));
        marker.setSnippet(String.format(Locale.getDefault(), "%.5f, %.5f",
                position.getLatitude(), position.getLongitude()));
        mapView.getOverlays().add(marker);
        mapView.invalidate();
        resolveAddressAsync(marker, position);
    }

    /**
     * La geocodificación inversa hace red/E/S, así que se hace fuera del hilo principal
     * y el título del marcador se actualiza luego con runOnUiThread.
     */
    @SuppressWarnings("deprecation")
    private void resolveAddressAsync(@NonNull Marker marker, @NonNull GeoPoint position) {
        backgroundExecutor.execute(() -> {
            String address = null;
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                    List<Address> results =
                            geocoder.getFromLocation(position.getLatitude(), position.getLongitude(), 1);
                    if (results != null && !results.isEmpty()) {
                        address = results.get(0).getAddressLine(0);
                    }
                }
            } catch (IOException ignored) {
                // Sin red o servicio no disponible: se deja el marcador con coordenadas.
            }
            final String finalAddress = address;
            if (finalAddress != null) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        marker.setTitle(finalAddress);
                        if (marker.isInfoWindowShown()) {
                            marker.showInfoWindow(); // refresca el texto si ya estaba abierto
                        }
                        mapView.invalidate();
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------- Imagen

    private void launchCamera() {
        try {
            takePictureLauncher.launch(null);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void onPictureTaken(@Nullable Bitmap bitmap) {
        if (bitmap == null) {
            return; // el usuario canceló
        }
        photoPreview.setImageBitmap(bitmap);
        photoPreview.setVisibility(View.VISIBLE);
        savePhotoAsync(bitmap);
    }

    /** Guarda la foto como JPEG en el almacenamiento interno, en un hilo secundario. */
    private void savePhotoAsync(@NonNull Bitmap bitmap) {
        backgroundExecutor.execute(() -> {
            File file = new File(getFilesDir(), "foto_" + System.currentTimeMillis() + ".jpg");
            boolean ok;
            try (FileOutputStream out = new FileOutputStream(file)) {
                ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            } catch (IOException e) {
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this,
                            saved ? R.string.photo_saved : R.string.photo_save_error,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ------------------------------------------------------------ Ciclo de vida

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (myLocationOverlay != null) {
            myLocationOverlay.enableMyLocation();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (myLocationOverlay != null) {
            myLocationOverlay.disableMyLocation();
        }
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
        backgroundExecutor.shutdown();
    }
}