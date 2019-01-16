// Copyright 2015-present 650 Industries. All rights reserved.

package expo.modules.location;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Looper;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import expo.core.ExportedModule;
import expo.core.ModuleRegistry;
import expo.core.Promise;
import expo.core.interfaces.ActivityEventListener;
import expo.core.interfaces.ActivityProvider;
import expo.core.interfaces.ExpoMethod;
import expo.core.interfaces.LifecycleEventListener;
import expo.core.interfaces.ModuleRegistryConsumer;
import expo.core.interfaces.services.EventEmitter;
import expo.core.interfaces.services.UIManager;
import expo.errors.CodedException;
import expo.interfaces.permissions.Permissions;
import expo.interfaces.taskManager.TaskManagerInterface;
import expo.modules.location.exceptions.LocationRequestRejectedException;
import expo.modules.location.exceptions.LocationRequestTimeoutException;
import expo.modules.location.exceptions.LocationSettingsUnsatisfiedException;
import expo.modules.location.exceptions.LocationUnauthorizedException;
import expo.modules.location.exceptions.LocationUnavailableException;
import expo.modules.location.taskConsumers.GeofencingTaskConsumer;
import expo.modules.location.taskConsumers.LocationTaskConsumer;
import expo.modules.location.utils.TimeoutObject;
import io.nlopez.smartlocation.OnGeocodingListener;
import io.nlopez.smartlocation.OnLocationUpdatedListener;
import io.nlopez.smartlocation.OnReverseGeocodingListener;
import io.nlopez.smartlocation.SmartLocation;
import io.nlopez.smartlocation.geocoding.utils.LocationAddress;
import io.nlopez.smartlocation.location.config.LocationParams;
import io.nlopez.smartlocation.location.utils.LocationState;

public class LocationModule extends ExportedModule implements ModuleRegistryConsumer, LifecycleEventListener, SensorEventListener, ActivityEventListener {
  private static final String TAG = LocationModule.class.getSimpleName();
  private static final String LOCATION_EVENT_NAME = "Expo.locationChanged";
  private static final String HEADING_EVENT_NAME = "Expo.headingChanged";
  private static final int CHECK_SETTINGS_REQUEST_CODE = 42;

  private static final String SHOW_USER_SETTINGS_DIALOG_KEY = "mayShowUserSettingsDialog";

  public static final int ACCURACY_LOWEST = 1;
  public static final int ACCURACY_LOW = 2;
  public static final int ACCURACY_BALANCED = 3;
  public static final int ACCURACY_HIGH = 4;
  public static final int ACCURACY_HIGHEST = 5;
  public static final int ACCURACY_BEST_FOR_NAVIGATION = 6;

  public static final int GEOFENCING_EVENT_ENTER = 1;
  public static final int GEOFENCING_EVENT_EXIT = 2;

  public static final int GEOFENCING_REGION_STATE_UNKNOWN = 0;
  public static final int GEOFENCING_REGION_STATE_INSIDE = 1;
  public static final int GEOFENCING_REGION_STATE_OUTSIDE = 2;

  private Context mContext;
  private SensorManager mSensorManager;
  private GeomagneticField mGeofield;

  private Map<Integer, LocationCallback> mLocationCallbacks = new HashMap<>();
  private Map<Integer, LocationRequest> mLocationRequests = new HashMap<>();
  private List<LocationActivityResultListener> mPendingLocationRequests = new ArrayList<>();

  // modules
  private EventEmitter mEventEmitter;
  private UIManager mUIManager;
  private Permissions mPermissions;
  private TaskManagerInterface mTaskManager;
  private ActivityProvider mActivityProvider;

  private float[] mGravity;
  private float[] mGeomagnetic;
  private int mHeadingId;
  private float mLastAzimut = 0;
  private int mAccuracy = 0;
  private long mLastUpdate = 0;
  private boolean mGeocoderPaused = false;

  private static final double DEGREE_DELTA = 0.0355; // in radians, about 2 degrees
  private static final float TIME_DELTA = 50; // in milliseconds

  public LocationModule(Context context) {
    super(context);
    mContext = context;
  }

  @Override
  public String getName() {
    return "ExpoLocation";
  }

  @Override
  public void setModuleRegistry(ModuleRegistry moduleRegistry) {
    if (mUIManager != null) {
      mUIManager.unregisterLifecycleEventListener(this);
    }

    mEventEmitter = moduleRegistry.getModule(EventEmitter.class);
    mUIManager = moduleRegistry.getModule(UIManager.class);
    mPermissions = moduleRegistry.getModule(Permissions.class);
    mTaskManager = moduleRegistry.getModule(TaskManagerInterface.class);
    mActivityProvider = moduleRegistry.getModule(ActivityProvider.class);

    if (mUIManager != null) {
      mUIManager.registerLifecycleEventListener(this);
    }
  }

  public static <BundleType extends BaseBundle> BundleType locationToBundle(Location location, Class<BundleType> bundleTypeClass) {
    try {
      BundleType map = bundleTypeClass.newInstance();
      BundleType coords = bundleTypeClass.newInstance();

      coords.putDouble("latitude", location.getLatitude());
      coords.putDouble("longitude", location.getLongitude());
      coords.putDouble("altitude", location.getAltitude());
      coords.putDouble("accuracy", location.getAccuracy());
      coords.putDouble("heading", location.getBearing());
      coords.putDouble("speed", location.getSpeed());

      if (map instanceof PersistableBundle) {
        ((PersistableBundle) map).putPersistableBundle("coords", (PersistableBundle) coords);
      } else if (map instanceof Bundle) {
        ((Bundle) map).putBundle("coords", (Bundle) coords);
        ((Bundle) map).putBoolean("mocked", location.isFromMockProvider());
      }
      map.putDouble("timestamp", location.getTime());

      return map;
    } catch (IllegalAccessException | InstantiationException e) {
      Log.e(TAG, "Unexpected exception was thrown when converting location to the bundle: " + e.toString());
      return null;
    }
  }

  private static Bundle addressToMap(Address address) {
    Bundle map = new Bundle();

    map.putString("city", address.getLocality());
    map.putString("street", address.getThoroughfare());
    map.putString("region", address.getAdminArea());
    map.putString("country", address.getCountryName());
    map.putString("postalCode", address.getPostalCode());
    map.putString("name", address.getFeatureName());
    map.putString("isoCountryCode", address.getCountryCode());

    return map;
  }

  private boolean isMissingPermissions() {
    return mPermissions == null
        || (
            mPermissions.getPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && mPermissions.getPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        );
  }

  @ExpoMethod
  public void getCurrentPositionAsync(final Map<String, Object> options, final Promise promise) {
    // Read options
    final Long timeout = options.containsKey("timeout") ? ((Double) options.get("timeout")).longValue() : null;
    final LocationRequest locationRequest = LocationHelpers.prepareLocationRequest(options);
    boolean showUserSettingsDialog = !options.containsKey(SHOW_USER_SETTINGS_DIALOG_KEY) || (boolean) options.get(SHOW_USER_SETTINGS_DIALOG_KEY);

    // Check for permissions
    if (isMissingPermissions()) {
      promise.reject(new LocationUnauthorizedException());
      return;
    }

    final TimeoutObject timeoutObject = new TimeoutObject(timeout);
    timeoutObject.onTimeout(new TimeoutObject.TimeoutListener() {
      @Override
      public void onTimeout() {
        promise.reject(new LocationRequestTimeoutException());
      }
    });
    timeoutObject.start();

    // Have location cached already?
    if (options.containsKey("maximumAge")) {
      final Double maximumAge = (Double) options.get("maximumAge");

      getLastKnownLocation(maximumAge, new OnSuccessListener<Location>() {
        @Override
        public void onSuccess(Location location) {
          if (location != null) {
            promise.resolve(locationToBundle(location, Bundle.class));
            timeoutObject.markDoneIfNotTimedOut();
          }
        }
      });
    }

    if (hasNetworkProviderEnabled() || !showUserSettingsDialog) {
      requestSingleLocation(locationRequest, timeoutObject, promise);
    } else {
      // Pending requests can ask the user to turn on improved accuracy mode in user's settings.
      addPendingLocationRequest(locationRequest, new LocationActivityResultListener() {
        @Override
        public void onResult(int resultCode) {
          if (resultCode == Activity.RESULT_OK) {
            requestSingleLocation(locationRequest, timeoutObject, promise);
          } else {
            promise.reject(new LocationSettingsUnsatisfiedException());
          }
        }
      });
    }
  }

  private boolean hasNetworkProviderEnabled() {
    LocationManager locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    return locationManager != null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
  }

  private void addPendingLocationRequest(LocationRequest locationRequest, LocationActivityResultListener listener) {
    // Add activity result listener to an array of pending requests.
    mPendingLocationRequests.add(listener);

    // If it's the first pending request, let's ask the user to turn on high accuracy location.
    if (mPendingLocationRequests.size() == 1) {
      resolveUserSettingsForRequest(locationRequest);
    }
  }

  private void resolveUserSettingsForRequest(LocationRequest locationRequest) {
    final Activity activity = mActivityProvider.getCurrentActivity();

    if (activity == null) {
      // Activity not found. It could have been called in a headless mode.
      executePendingRequests(Activity.RESULT_CANCELED);
      return;
    }

    LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest);
    SettingsClient client = LocationServices.getSettingsClient(mContext);
    Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

    task.addOnSuccessListener(new OnSuccessListener<LocationSettingsResponse>() {
      @Override
      public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
        // All location settings requirements are satisfied.
        executePendingRequests(Activity.RESULT_OK);
      }
    });

    task.addOnFailureListener(new OnFailureListener() {
      @Override
      public void onFailure(@NonNull Exception e) {
        int statusCode = ((ApiException) e).getStatusCode();

        switch (statusCode) {
          case CommonStatusCodes.RESOLUTION_REQUIRED:
            // Location settings are not satisfied, but this can be fixed by showing the user a dialog.
            // Show the dialog by calling startResolutionForResult(), and check the result in onActivityResult().

            try {
              ResolvableApiException resolvable = (ResolvableApiException) e;

              mUIManager.registerActivityEventListener(LocationModule.this);
              resolvable.startResolutionForResult(activity, CHECK_SETTINGS_REQUEST_CODE);
            } catch (IntentSender.SendIntentException sendEx) {
              // Ignore the error.
              executePendingRequests(Activity.RESULT_CANCELED);
            }
            break;
          default:
            // Location settings are not satisfied. However, we have no way to fix the settings so we won't show the dialog.
            executePendingRequests(Activity.RESULT_CANCELED);
            break;
        }
      }
    });
  }

  private void requestSingleLocation(final LocationRequest locationRequest, final TimeoutObject timeoutObject, final Promise promise) {
    // we want just one update
    locationRequest.setNumUpdates(1);

    requestLocationUpdates(locationRequest, null, new LocationRequestCallbacks() {
      @Override
      public void onLocationChanged(Location location) {
        if (timeoutObject.markDoneIfNotTimedOut()) {
          promise.resolve(locationToBundle(location, Bundle.class));
        }
      }

      @Override
      public void onLocationError(CodedException exception) {
        if (timeoutObject.markDoneIfNotTimedOut()) {
          promise.reject(exception);
        }
      }

      @Override
      public void onRequestFailed(CodedException exception) {
        if (timeoutObject.markDoneIfNotTimedOut()) {
          promise.reject(exception);
        }
      }
    });
  }

  private void requestContinuousUpdates(final LocationRequest locationRequest, final int watchId, final Promise promise) {
    requestLocationUpdates(locationRequest, watchId, new LocationRequestCallbacks() {
      @Override
      public void onLocationChanged(Location location) {
        Bundle response = new Bundle();

        response.putBundle("location", locationToBundle(location, Bundle.class));
        sendLocationResponse(watchId, response);
      }

      @Override
      public void onRequestSuccess() {
        promise.resolve(null);
      }

      @Override
      public void onRequestFailed(CodedException exception) {
        promise.reject(exception);
      }
    });
  }

  private boolean startWatching() {
    if (mContext == null) {
      return false;
    }

    // if permissions not granted it won't work anyway, but this can be invoked when permission dialog disappears
    if (!isMissingPermissions()) {
      mGeocoderPaused = false;
    }

    // Resume paused location updates
    resumeLocationUpdates();

    return true;
  }

  private void stopWatching() {
    if (mContext == null) {
      return;
    }

    // if permissions not granted it won't work anyway, but this can be invoked when permission dialog appears
    if (Geocoder.isPresent() && !isMissingPermissions()) {
      SmartLocation.with(mContext).geocoding().stop();
      mGeocoderPaused = true;
    }

    for (Integer requestId : mLocationCallbacks.keySet()) {
      pauseLocationUpdatesForRequest(requestId);
    }
  }

  @ExpoMethod
  public void getProviderStatusAsync(final Promise promise) {
    if (mContext == null) {
      promise.reject("E_CONTEXT_UNAVAILABLE", "Context is not available");
    }

    LocationState state = SmartLocation.with(mContext).location().state();

    Bundle map = new Bundle();

    map.putBoolean("locationServicesEnabled", state.locationServicesEnabled()); // If location is off
    map.putBoolean("gpsAvailable", state.isGpsAvailable()); // If GPS provider is enabled
    map.putBoolean("networkAvailable", state.isNetworkAvailable()); // If network provider is enabled
    map.putBoolean("passiveAvailable", state.isPassiveAvailable()); // If passive provider is enabled

    promise.resolve(map);
  }

  // Start Compass Module

  @ExpoMethod
  public void watchDeviceHeading(final int watchId, final Promise promise) {
    mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
    this.mHeadingId = watchId;
    startHeadingUpdate();
    promise.resolve(null);
  }

  public void startHeadingUpdate() {
    if (mSensorManager == null || mContext == null) {
      return;
    }

    SmartLocation.LocationControl locationControl = SmartLocation.with(mContext).location().oneFix().config(LocationParams.BEST_EFFORT);
    Location currLoc = locationControl.getLastLocation();
    if (currLoc != null) {
      mGeofield = new GeomagneticField(
          (float) currLoc.getLatitude(),
          (float) currLoc.getLongitude(),
          (float) currLoc.getAltitude(),
          System.currentTimeMillis());
    } else {
      locationControl.start(new OnLocationUpdatedListener() {
        @Override
        public void onLocationUpdated(Location location) {
          mGeofield = new GeomagneticField(
              (float) location.getLatitude(),
              (float) location.getLongitude(),
              (float) location.getAltitude(),
              System.currentTimeMillis());
        }
      });
    }
    mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
        SensorManager.SENSOR_DELAY_NORMAL);
    mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
  }

  public void onSensorChanged(SensorEvent event) {
    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
      mGravity = event.values;
    if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
      mGeomagnetic = event.values;
    if (mGravity != null && mGeomagnetic != null) {
      sendUpdate();
    }
  }

  private void sendUpdate() {
    float R[] = new float[9];
    float I[] = new float[9];
    boolean success = SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic);

    if (success) {
      float orientation[] = new float[3];
      SensorManager.getOrientation(R, orientation);

      // Make sure Delta is big enough to warrant an update
      // Currently: 50ms and ~2 degrees of change (android has a lot of useless updates block up the sending)
      if ((Math.abs(orientation[0] - mLastAzimut)) > DEGREE_DELTA && (System.currentTimeMillis() - mLastUpdate) > TIME_DELTA) {
        mLastAzimut = orientation[0];
        mLastUpdate = System.currentTimeMillis();
        float magneticNorth = calcMagNorth(orientation[0]);
        float trueNorth = calcTrueNorth(magneticNorth);

        // Write data to send back to React
        Bundle response = new Bundle();
        Bundle heading = new Bundle();

        response.putInt("watchId", mHeadingId);

        heading.putDouble("trueHeading", trueNorth);
        heading.putDouble("magHeading", magneticNorth);
        heading.putInt("accuracy", mAccuracy);
        response.putBundle("heading", heading);

        mEventEmitter.emit(HEADING_EVENT_NAME, response);
      }
    }
  }

  private float calcMagNorth(float azimut) {
    float azimutDeg = (float) Math.toDegrees(azimut);
    return (azimutDeg + 360) % 360;
  }

  private float calcTrueNorth(float magNorth) {
    // Need to request geo location info to calculate true north
    if (isMissingPermissions() || mGeofield == null) {
      return -1;
    }
    return magNorth + mGeofield.getDeclination();
  }

  private void stopHeadingWatch() {
    if (mSensorManager == null) {
      return;
    }
    mSensorManager.unregisterListener(this);
  }

  private void destroyHeadingWatch() {
    stopHeadingWatch();
    mSensorManager = null;
    mGravity = null;
    mGeomagnetic = null;
    mGeofield = null;
    mHeadingId = 0;
    mLastAzimut = 0;
    mAccuracy = 0;
  }

  // Android returns 4 different values for accuracy
  // 3: high accuracy, 2: medium, 1: low, 0: none
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    mAccuracy = accuracy;
  }
  // End Compass

  @ExpoMethod
  public void watchPositionImplAsync(final int watchId, final Map<String, Object> options, final Promise promise) {
    // Check for permissions
    if (isMissingPermissions()) {
      promise.reject(new LocationUnauthorizedException());
      return;
    }

    final LocationRequest locationRequest = LocationHelpers.prepareLocationRequest(options);
    boolean showUserSettingsDialog = !options.containsKey(SHOW_USER_SETTINGS_DIALOG_KEY) || (boolean) options.get(SHOW_USER_SETTINGS_DIALOG_KEY);

    if (hasNetworkProviderEnabled() || !showUserSettingsDialog) {
      requestContinuousUpdates(locationRequest, watchId, promise);
    } else {
      // Pending requests can ask the user to turn on improved accuracy mode in user's settings.
      addPendingLocationRequest(locationRequest, new LocationActivityResultListener() {
        @Override
        public void onResult(int resultCode) {
          if (resultCode == Activity.RESULT_OK) {
            requestContinuousUpdates(locationRequest, watchId, promise);
          } else {
            promise.reject(new LocationSettingsUnsatisfiedException());
          }
        }
      });
    }
  }

  // TODO: Stop sending watchId from JS since we ignore it.
  @ExpoMethod
  public void removeWatchAsync(final int watchId, final Promise promise) {
    if (isMissingPermissions()) {
      promise.reject(new LocationUnauthorizedException());
      return;
    }

    // Check if we want to stop watching location or compass
    if (watchId == mHeadingId) {
      destroyHeadingWatch();
    } else {
      removeLocationUpdatesForRequest(watchId);
    }

    promise.resolve(null);
  }

  @ExpoMethod
  public void geocodeAsync(final String address, final Promise promise) {
    if (mGeocoderPaused) {
      promise.reject("E_CANNOT_GEOCODE", "Geocoder is not running.");
      return;
    }

    if (isMissingPermissions()) {
      promise.reject(new LocationUnauthorizedException());
      return;
    }

    if (Geocoder.isPresent()) {
      SmartLocation.with(mContext).geocoding()
          .direct(address, new OnGeocodingListener() {
            @Override
            public void onLocationResolved(String s, List<LocationAddress> list) {
              List<Bundle> results = new ArrayList<>(list.size());

              for (LocationAddress locationAddress : list) {
                Bundle coords = new Bundle();
                Location location = locationAddress.getLocation();

                coords.putDouble("latitude", location.getLatitude());
                coords.putDouble("longitude", location.getLongitude());
                coords.putDouble("altitude", location.getAltitude());
                coords.putDouble("accuracy", location.getAccuracy());
                results.add(coords);
              }

              SmartLocation.with(mContext).geocoding().stop();
              promise.resolve(results);
            }
          });
    } else {
      promise.reject("E_NO_GEOCODER", "Geocoder service is not available for this device.");
    }
  }

  @ExpoMethod
  public void reverseGeocodeAsync(final Map<String, Object> locationMap, final Promise promise) {
    if (mGeocoderPaused) {
      promise.reject("E_CANNOT_GEOCODE", "Geocoder is not running.");
      return;
    }

    if (isMissingPermissions()) {
      promise.reject(new LocationUnauthorizedException());
      return;
    }

    Location location = new Location("");
    location.setLatitude((double) locationMap.get("latitude"));
    location.setLongitude((double) locationMap.get("longitude"));

    if (Geocoder.isPresent()) {
      SmartLocation.with(mContext).geocoding()
          .reverse(location, new OnReverseGeocodingListener() {
            @Override
            public void onAddressResolved(Location original, List<Address> addresses) {
              List<Bundle> results = new ArrayList<>(addresses.size());

              for (Address address : addresses) {
                results.add(addressToMap(address));
              }

              SmartLocation.with(mContext).geocoding().stop();
              promise.resolve(results);
            }
          });
    } else {
      promise.reject("E_NO_GEOCODER", "Geocoder service is not available for this device.");
    }
  }

  @ExpoMethod
  public void requestPermissionsAsync(final Promise promise) {
    if (mPermissions == null) {
      promise.reject("E_NO_PERMISSIONS", "Permissions module is null. Are you sure all the installed Expo modules are properly linked?");
      return;
    }

    mPermissions.askForPermissions(
        new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        },
        new Permissions.PermissionsRequestListener() {
          @Override
          public void onPermissionsResult(int[] results) {
            for (int result : results) {
              // we need at least one of asked permissions to be granted
              if (result == PackageManager.PERMISSION_GRANTED) {
                promise.resolve(null);
                return;
              }
            }
            promise.reject(new LocationUnauthorizedException());
          }
        });
  }

  @ExpoMethod
  public void enableNetworkProviderAsync(final Promise promise) {
    LocationRequest locationRequest = LocationHelpers.prepareLocationRequest(new HashMap<String, Object>());
    resolveUserSettingsForRequest(locationRequest);
  }

  //region hasServicesEnabled

  @ExpoMethod
  public void hasServicesEnabledAsync(final Promise promise) {
    boolean servicesEnabled = LocationHelpers.isAnyProviderAvailable(getContext());
    promise.resolve(servicesEnabled);
  }

  //endregion
  //region Background location

  @ExpoMethod
  public void startLocationUpdatesAsync(String taskName, Map<String, Object> options, final Promise promise) {
    try {
      mTaskManager.registerTask(taskName, LocationTaskConsumer.class, options);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ExpoMethod
  public void stopLocationUpdatesAsync(String taskName, final Promise promise) {
    try {
      mTaskManager.unregisterTask(taskName, LocationTaskConsumer.class);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ExpoMethod
  public void hasStartedLocationUpdatesAsync(String taskName, final Promise promise) {
    promise.resolve(mTaskManager.taskHasConsumerOfClass(taskName, LocationTaskConsumer.class));
  }

  //endregion Background location
  //region Geofencing

  @ExpoMethod
  public void startGeofencingAsync(String taskName, Map<String, Object> options, final Promise promise) {
    try {
      mTaskManager.registerTask(taskName, GeofencingTaskConsumer.class, options);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ExpoMethod
  public void stopGeofencingAsync(String taskName, final Promise promise) {
    try {
      mTaskManager.unregisterTask(taskName, GeofencingTaskConsumer.class);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ExpoMethod
  public void hasStartedGeofencingAsync(String taskName, final Promise promise) {
    promise.resolve(mTaskManager.taskHasConsumerOfClass(taskName, GeofencingTaskConsumer.class));
  }

  //endregion Geofencing
  //region Requesting for location updates

  private void getLastKnownLocation(final Double maximumAge, final OnSuccessListener<Location> callback) {
    final FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(mContext);

    try {
      locationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
        @Override
        public void onSuccess(Location location) {
          if (location != null && (maximumAge == null || System.currentTimeMillis() - location.getTime() < maximumAge)) {
            callback.onSuccess(location);
          } else {
            callback.onSuccess(null);
          }
        }
      });
    } catch (SecurityException e) {
      callback.onSuccess(null);
    }
  }

  private void requestLocationUpdates(final LocationRequest locationRequest, Integer requestId, final LocationRequestCallbacks callbacks) {
    final FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(mContext);

    LocationCallback locationCallback = new LocationCallback() {
      @Override
      public void onLocationResult(LocationResult locationResult) {
        Location location = locationResult != null ? locationResult.getLastLocation() : null;

        if (location != null) {
          callbacks.onLocationChanged(location);
        }
      }

      @Override
      public void onLocationAvailability(LocationAvailability locationAvailability) {
        if (!locationAvailability.isLocationAvailable()) {
          callbacks.onLocationError(new LocationUnavailableException());
        }
      }
    };

    if (requestId != null) {
      // Save location callback and request so we will be able to pause/resume receiving updates.
      mLocationCallbacks.put(requestId, locationCallback);
      mLocationRequests.put(requestId, locationRequest);
    }

    try {
      locationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper());
      callbacks.onRequestSuccess();
    } catch (SecurityException e) {
      callbacks.onRequestFailed(new LocationRequestRejectedException(e));
    }
  }

  private void pauseLocationUpdatesForRequest(Integer requestId) {
    final FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(mContext);

    if (mLocationCallbacks.containsKey(requestId)) {
      LocationCallback locationCallback = mLocationCallbacks.get(requestId);
      locationClient.removeLocationUpdates(locationCallback);
    }
  }

  private void resumeLocationUpdates() {
    final FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(mContext);

    for (Integer requestId : mLocationCallbacks.keySet()) {
      LocationCallback locationCallback = mLocationCallbacks.get(requestId);
      LocationRequest locationRequest = mLocationRequests.get(requestId);

      if (locationCallback != null && locationRequest != null) {
        try {
          locationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper());
        } catch (SecurityException e) {
          Log.e(TAG, "Error occurred while resuming location updates: " + e.toString());
        }
      }
    }
  }

  private void removeLocationUpdatesForRequest(Integer requestId) {
    pauseLocationUpdatesForRequest(requestId);
    mLocationCallbacks.remove(requestId);
    mLocationRequests.remove(requestId);
  }

  private void sendLocationResponse(int watchId, Bundle response) {
    response.putInt("watchId", watchId);
    mEventEmitter.emit(LOCATION_EVENT_NAME, response);
  }

  private void executePendingRequests(int resultCode) {
    // Propagate result to pending location requests.
    for (LocationActivityResultListener listener : mPendingLocationRequests) {
      listener.onResult(resultCode);
    }
    mPendingLocationRequests.clear();
  }

  //endregion
  //region ActivityEventListener

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
    if (requestCode != CHECK_SETTINGS_REQUEST_CODE) {
      return;
    }
    executePendingRequests(resultCode);
    mUIManager.unregisterActivityEventListener(this);
  }

  @Override
  public void onNewIntent(Intent intent) {}

  //endregion
  //region LifecycleEventListener

  @Override
  public void onHostResume() {
    startWatching();
    startHeadingUpdate();
  }

  @Override
  public void onHostPause() {
    stopWatching();
    stopHeadingWatch();
  }

  @Override
  public void onHostDestroy() {
    stopWatching();
    stopHeadingWatch();
  }

  //endregion
}
