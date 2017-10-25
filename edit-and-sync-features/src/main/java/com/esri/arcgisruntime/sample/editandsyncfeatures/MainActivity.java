/* Copyright 2017 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.esri.arcgisruntime.sample.editandsyncfeatures;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.esri.arcgisruntime.ArcGISRuntimeEnvironment;
import com.esri.arcgisruntime.concurrent.Job;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.data.Feature;
import com.esri.arcgisruntime.data.FeatureQueryResult;
import com.esri.arcgisruntime.data.Geodatabase;
import com.esri.arcgisruntime.data.GeodatabaseFeatureTable;
import com.esri.arcgisruntime.data.QueryParameters;
import com.esri.arcgisruntime.data.ServiceFeatureTable;
import com.esri.arcgisruntime.data.TileCache;
import com.esri.arcgisruntime.geometry.Envelope;
import com.esri.arcgisruntime.geometry.GeometryType;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.layers.ArcGISTiledLayer;
import com.esri.arcgisruntime.layers.FeatureLayer;
import com.esri.arcgisruntime.layers.Layer;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.symbology.SimpleLineSymbol;
import com.esri.arcgisruntime.tasks.geodatabase.GenerateGeodatabaseJob;
import com.esri.arcgisruntime.tasks.geodatabase.GenerateGeodatabaseParameters;
import com.esri.arcgisruntime.tasks.geodatabase.GeodatabaseSyncTask;
import com.esri.arcgisruntime.tasks.geodatabase.SyncGeodatabaseJob;
import com.esri.arcgisruntime.tasks.geodatabase.SyncGeodatabaseParameters;
import com.esri.arcgisruntime.tasks.geodatabase.SyncLayerOption;

public class MainActivity extends AppCompatActivity {

  private final String TAG = MainActivity.class.getSimpleName();
  private final String[] reqPermission = new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE };

  private RelativeLayout mProgressLayout;
  private TextView mProgressTextView;
  private ProgressBar mProgressBar;
  private Button mGeodatabaseButton;
  private TextView mEditCount;

  private MapView mMapView;
  private FeatureLayer serviceFeatureLayer;
  private int serviceLayerId = 0;
  private GraphicsOverlay mGraphicsOverlay;
  private GeodatabaseSyncTask mGeodatabaseSyncTask;
  private Geodatabase mGeodatabase;
  private GeodatabaseFeatureTable pointsGeodatabaseFeatureTable;

  private List<Feature> mSelectedFeatures;
  private EditState mCurrentEditState;
  private Snackbar moveFeatureSnackbar;

  /**
   * Generates a local geodatabase and sets it to the map.
   */
  private void generateGeodatabase() {
    updateProgress(0);

    // Remove the service layer
    if (serviceFeatureLayer != null) {
      mMapView.getMap().getOperationalLayers().remove(serviceFeatureLayer);
      serviceFeatureLayer = null;
    }

    // 1- start with creating a geodatabase sync task using URL of sync-enabled service
    mGeodatabaseSyncTask = new GeodatabaseSyncTask(getString(R.string.wildfire_sync));
    mGeodatabaseSyncTask.loadAsync();
    mGeodatabaseSyncTask.addDoneLoadingListener(new Runnable() {
      @Override public void run() {

        // show the extent used as a graphic
        final SimpleLineSymbol boundarySymbol = new SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.RED, 5);
        final Envelope extent = mMapView.getVisibleArea().getExtent();
        Graphic boundary = new Graphic(extent, boundarySymbol);
        mGraphicsOverlay.getGraphics().add(boundary);

        // 2 - Set up parameters for the task, passing in the defined map extent
        final ListenableFuture<GenerateGeodatabaseParameters> defaultParameters =
            mGeodatabaseSyncTask.createDefaultGenerateGeodatabaseParametersAsync(extent);
        defaultParameters.addDoneListener(new Runnable() {
          @Override public void run() {
            try {

              GenerateGeodatabaseParameters parameters = defaultParameters.get();

              // 3 - Configure other parameters:
              // - don't need attachments,
              parameters.setReturnAttachments(false);

              // 4 - Create and start the job
              // - define the local path where the geodatabase will be stored
              final String localGeodatabasePath = getCacheDir().toString() + File.separator + getString(R.string.file_name);

              final GenerateGeodatabaseJob generateGeodatabaseJob = mGeodatabaseSyncTask
                  .generateGeodatabaseAsync(parameters, localGeodatabasePath);
              generateGeodatabaseJob.start();
              generateGeodatabaseJob.addProgressChangedListener(new Runnable() {
                @Override public void run() {
                  updateProgress(generateGeodatabaseJob.getProgress());
                }
              });

              // get geodatabase when done
              generateGeodatabaseJob.addJobDoneListener(new Runnable() {
                @Override public void run() {
                  updateProgress(generateGeodatabaseJob.getProgress());

                  if (generateGeodatabaseJob.getStatus() == Job.Status.SUCCEEDED) {

                    // 5 - When Job is complete, get the Geodatabase from the Jobs result.
                    mGeodatabase = generateGeodatabaseJob.getResult();
                    mGeodatabase.loadAsync();
                    mGeodatabase.addDoneLoadingListener(new Runnable() {
                      @Override public void run() {

                        if (mGeodatabase.getLoadStatus() == LoadStatus.LOADED) {

                          // 6 - Create a GeodatabaseFeatureTable from the downloaded Geodatabase, then
                          //     make a FeatureLayer from that and add to the map.
                          pointsGeodatabaseFeatureTable = mGeodatabase.getGeodatabaseFeatureTableByServiceLayerId(serviceLayerId);
                          pointsGeodatabaseFeatureTable.loadAsync();
                          FeatureLayer geodatabaseFeatureLayer = new FeatureLayer(pointsGeodatabaseFeatureTable);
                          mMapView.getMap().getOperationalLayers().add(geodatabaseFeatureLayer);

                          // make it selectable
                          geodatabaseFeatureLayer.setSelectionColor(Color.CYAN);
                          geodatabaseFeatureLayer.setSelectionWidth(5.0);

                          mGeodatabaseButton.setVisibility(View.GONE);
                          Log.i(TAG, "Local geodatabase stored at: " + localGeodatabasePath);
                        } else {
                          Log.e(TAG, "Error loading geodatabase: " + mGeodatabase.getLoadError().getMessage());
                        }
                      }
                    });
                    // set edit state to ready
                    mCurrentEditState = EditState.READY_TO_SYNC;
                  } else if (generateGeodatabaseJob.getError() != null) {
                    Log.e(TAG, "Error generating geodatabase: " + generateGeodatabaseJob.getError().getMessage());
                    Snackbar.make(mMapView, "Error generating geodatabase: " +
                        generateGeodatabaseJob.getError().getMessage(), Snackbar.LENGTH_LONG).show();
                    mProgressLayout.setVisibility(View.INVISIBLE);
                  } else {
                    Log.e(TAG, "Unknown Error generating geodatabase");
                    Snackbar.make(mMapView, "Unknown Error generating geodatabase", Snackbar.LENGTH_LONG).show();
                    mProgressLayout.setVisibility(View.INVISIBLE);
                  }
                }
              });
            } catch (InterruptedException | ExecutionException e) {
              Log.e(TAG, "Error generating geodatabase parameters : " + e.getMessage());
              Snackbar.make(mMapView, "Error generating geodatabase parameters: " + e.getMessage(),
                  Snackbar.LENGTH_LONG).show();
              mProgressLayout.setVisibility(View.INVISIBLE);
            }
          }
        });
      }
    });
  }

  /**
   * Moves selected features to the given point.
   *
   * @param point contains an ArcGIS map point
   */
  private void moveSelectedFeatureTo(Point point) {

    // 7 - Set Geometry on the Feature and call updateFeatureAsync
    for (Feature feature : mSelectedFeatures) {
      feature.setGeometry(point);
      feature.getFeatureTable().updateFeatureAsync(feature);
    }

    // Empty the list of selected features and update the UI
    mSelectedFeatures.clear();
    moveFeatureSnackbar.dismiss();
    mCurrentEditState = EditState.READY_TO_SYNC;
    mGeodatabaseButton.setText(R.string.sync_geodatabase_button_text);
    mGeodatabaseButton.setVisibility(View.VISIBLE);

    mEditCount.setVisibility(View.VISIBLE);
    updateFeatureCountLabel();
  }

  private void updateFeatureCountLabel() {
    Log.i(TAG, String.format("hasLocalEdits mGeodatabase.hasLocalEdits: %B", mGeodatabase.hasLocalEdits()));

    if (mGeodatabase.hasLocalEdits()) {

      mEditCount.setVisibility(View.VISIBLE);


      // 8 - Check the number of features that have been updated locally since the last sync operation.
      final ListenableFuture<Long> updatedFeaturesCountAsync = pointsGeodatabaseFeatureTable.getUpdatedFeaturesCountAsync();
      updatedFeaturesCountAsync.addDoneListener(new Runnable() {
        @Override public void run() {
          try {

            // Update the UI with the result.
            long updatedCount = updatedFeaturesCountAsync.get();
            Log.i(TAG, String.format("updateFeatureCountLabel : %d", updatedCount));
            mEditCount.setText(String.format("Updated Features: %d", updatedCount));

          } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
          }
        }
      });

    } else {
      Log.i(TAG, String.format("hasLocalEdits : %B", false));
      mEditCount.setVisibility(View.INVISIBLE);
      mEditCount.setText("");
    }
  }

  /**
   * Syncs changes made on either the local or web service geodatabase with each other.
   */
  private void syncGeodatabase() {
    updateProgress(0);

    // 9 - Create SyncGeodatabaseParameters and configure
    SyncGeodatabaseParameters syncGeodatabaseParameters = new SyncGeodatabaseParameters();
    syncGeodatabaseParameters.setSyncDirection(SyncGeodatabaseParameters.SyncDirection.BIDIRECTIONAL);
    syncGeodatabaseParameters.setRollbackOnFailure(false);

    // Get the layer ID for each feature table in the geodatabase, then add to the sync job
    for (GeodatabaseFeatureTable geodatabaseFeatureTable : mGeodatabase.getGeodatabaseFeatureTables()) {
      long serviceLayerId = geodatabaseFeatureTable.getServiceLayerId();
      SyncLayerOption syncLayerOption = new SyncLayerOption(serviceLayerId);
      syncGeodatabaseParameters.getLayerOptions().add(syncLayerOption);
    }

    // 10 - Create a SyncGeodatabaseJob from the same GeodatabaseSyncTask I used before and start it.
    final SyncGeodatabaseJob syncGeodatabaseJob = mGeodatabaseSyncTask.
        syncGeodatabaseAsync(syncGeodatabaseParameters, mGeodatabase);

    syncGeodatabaseJob.start();

    syncGeodatabaseJob.addProgressChangedListener(new Runnable() {
      @Override public void run() {
        updateProgress(syncGeodatabaseJob.getProgress());
      }
    });

    syncGeodatabaseJob.addJobDoneListener(new Runnable() {
      @Override public void run() {
        if (syncGeodatabaseJob.getStatus() == Job.Status.SUCCEEDED) {
          Snackbar.make(mMapView, "Sync complete", Snackbar.LENGTH_SHORT).show();
          mGeodatabaseButton.setVisibility(View.INVISIBLE);
          pointsGeodatabaseFeatureTable.getFeatureLayer().clearSelection();

          updateFeatureCountLabel();
        } else {
          Log.e(TAG, "Database did not sync correctly!");
          Snackbar.make(mMapView, "Database did not sync correctly!", Snackbar.LENGTH_LONG).show();
        }
      }
    });
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    ArcGISRuntimeEnvironment.setLicense(BuildConfig.LICENSE_STRING);

    // set edit state to not ready until geodatabase job has completed successfully
    mCurrentEditState = EditState.NO_LOCAL_GEODATABASE;

    mEditCount = (TextView) findViewById(R.id.editCountLabel);

    // create a map view and add a map
    mMapView = (MapView) findViewById(R.id.mapView);
    moveFeatureSnackbar = Snackbar.make(mMapView, "Tap on map to move feature", Snackbar.LENGTH_LONG);

    // create a graphics overlay and symbol to mark the extent
    mGraphicsOverlay = new GraphicsOverlay();
    mMapView.getGraphicsOverlays().add(mGraphicsOverlay);

    // inflate button and progress layout
    mGeodatabaseButton = (Button) findViewById(R.id.geodatabaseButton);
    mProgressLayout = (RelativeLayout) findViewById(R.id.progressLayout);
    mProgressTextView = (TextView) findViewById(R.id.progressTextView);
    mProgressBar = (ProgressBar) findViewById(R.id.taskProgressBar);

    // add listener to handle generate/sync geodatabase button
    mGeodatabaseButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        if (mCurrentEditState == EditState.NO_LOCAL_GEODATABASE) {
          generateGeodatabase();
        } else if (mCurrentEditState == EditState.READY_TO_SYNC) {
          syncGeodatabase();
        }
      }
    });

    // add listener to handle motion events, which only responds once a geodatabase is loaded
    mMapView.setOnTouchListener(
        new DefaultMapViewOnTouchListener(MainActivity.this, mMapView) {
          @Override
          public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
            if (mCurrentEditState == EditState.READY_TO_SYNC) {
              selectFeaturesAt(mapPointFrom(motionEvent), 10);
            } else if (mCurrentEditState == EditState.IS_EDITING) {
              moveSelectedFeatureTo(mapPointFrom(motionEvent));
            }
            return true;
          }
        });

    // request write permission to access local TileCache
    if (ContextCompat.checkSelfPermission(MainActivity.this, reqPermission[0]) != PackageManager.PERMISSION_GRANTED) {
      // request permission
      int requestCode = 2;
      ActivityCompat.requestPermissions(MainActivity.this, reqPermission, requestCode);
    } else {
      loadMap();
    }
  }

  /**
   * Load local tile cache.
   */
  private void loadMap() {
    // use local tile package for the base map
    TileCache sanFranciscoTileCache = new TileCache(
        Environment.getExternalStorageDirectory() + getString(R.string.san_francisco_tpk));
    ArcGISTiledLayer tiledLayer = new ArcGISTiledLayer(sanFranciscoTileCache);
    final ArcGISMap map = new ArcGISMap(new Basemap(tiledLayer));

    // create a service feature table to show the online layer to begin with
    ServiceFeatureTable serviceFeatureTable = new ServiceFeatureTable(getResources().getString(R.string.wildfire_service));

    // create the feature layer using the service feature table
    serviceFeatureLayer = new FeatureLayer(serviceFeatureTable);

    // add the layer to the map
    map.getOperationalLayers().add(serviceFeatureLayer);

    mMapView.setMap(map);
  }

  /**
   * Controls visibility and updates to the UI of job progress.
   *
   * @param progress from either generate and sync jobs
   */
  private void updateProgress(int progress) {
    if (progress < 100) {
      mProgressBar.setProgress(progress);
      mProgressLayout.setVisibility(View.VISIBLE);

      if (progress == 0) {
        mProgressTextView.setText(getString(R.string.progress_starting));
      } else if (progress < 10) {
        mProgressTextView.setText(getString(R.string.progress_started));
      } else {
        mProgressTextView.setText(getString(R.string.progress_syncing));
      }
    } else {
      mProgressTextView.setText(getString(R.string.progress_done));
      mProgressLayout.setVisibility(View.INVISIBLE);
    }
  }

  /**
   * Queries the features at the tapped point within a certain tolerance.
   *
   * @param point     contains an ArcGIS map point
   * @param tolerance distance from point within which features will be selected
   */
  private void selectFeaturesAt(Point point, int tolerance) {
    // define the tolerance for identifying the feature
    final double mapTolerance = tolerance * mMapView.getUnitsPerDensityIndependentPixel();

    // create objects required to do a selection with a query
    Envelope envelope = new Envelope(point.getX() - mapTolerance, point.getY() - mapTolerance,
        point.getX() + mapTolerance, point.getY() + mapTolerance, mMapView.getSpatialReference());
    QueryParameters query = new QueryParameters();
    query.setGeometry(envelope);
    mSelectedFeatures = new ArrayList<>();

    // select features within the envelope for all features on the map
    for (Layer layer : mMapView.getMap().getOperationalLayers()) {
      final FeatureLayer featureLayer = (FeatureLayer) layer;
      final ListenableFuture<FeatureQueryResult> featureQueryResultFuture = featureLayer
          .selectFeaturesAsync(query, FeatureLayer.SelectionMode.NEW);
      // add done loading listener to fire when the selection returns
      featureQueryResultFuture.addDoneListener(new Runnable() {
        @Override
        public void run() {

          // Get the selected features
          final ListenableFuture<FeatureQueryResult> featureQueryResultFuture = featureLayer.getSelectedFeaturesAsync();
          featureQueryResultFuture.addDoneListener(new Runnable() {
            @Override public void run() {
              try {
                FeatureQueryResult layerFeatures = featureQueryResultFuture.get();
                for (Feature feature : layerFeatures) {

                  // Only select points for editing
                  if (feature.getGeometry().getGeometryType() == GeometryType.POINT) {
                    mSelectedFeatures.add(feature);
                  }
                }
              } catch (Exception e) {
                Log.e(getResources().getString(R.string.app_name), "Select feature failed: " + e.getMessage());
              }
            }
          });

          // set current edit state to editing
          mCurrentEditState = EditState.IS_EDITING;

          // Inform user
          moveFeatureSnackbar.show();

        }
      });
    }
  }

  /**
   * Converts motion event to an ArcGIS map point.
   *
   * @param motionEvent containing coordinates of an Android screen point
   * @return a corresponding map point in the place
   */
  private Point mapPointFrom(MotionEvent motionEvent) {
    // get the screen point
    android.graphics.Point screenPoint = new android.graphics.Point(Math.round(motionEvent.getX()),
        Math.round(motionEvent.getY()));
    // return the point that was clicked in map coordinates
    return mMapView.screenToLocation(screenPoint);
  }

  @Override
  protected void onPause() {
    super.onPause();
    mMapView.pause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    mMapView.resume();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      // Write permission was granted, so load TileCache
      loadMap();
    } else {
      // If permission was denied, show message to inform user write permission is required and remove Generate
      // Geodatabase button
      Snackbar.make(mMapView, getResources().getString(R.string.write_permission), Snackbar.LENGTH_SHORT).show();
      mGeodatabaseButton.setVisibility(View.GONE);
    }
  }

  // Enumeration to track editing of points
  public enum EditState {
    NO_LOCAL_GEODATABASE, // Geodatabase has not yet been generated
    IS_EDITING, // A feature is in the process of being moved
    READY_TO_SYNC // The geodatabase is ready for synchronization or further edits
  }
}
