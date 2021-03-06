/*
 *  This file is part of eduVPN.
 *
 *     eduVPN is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     eduVPN is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with eduVPN.  If not, see <http://www.gnu.org/licenses/>.
 */

package nl.eduvpn.app.fragment;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import nl.eduvpn.app.Constants;
import nl.eduvpn.app.EduVPNApplication;
import nl.eduvpn.app.R;
import nl.eduvpn.app.adapter.ProviderAdapter;
import nl.eduvpn.app.entity.AuthorizationType;
import nl.eduvpn.app.entity.DiscoveredAPI;
import nl.eduvpn.app.entity.Instance;
import nl.eduvpn.app.service.APIService;
import nl.eduvpn.app.service.ConfigurationService;
import nl.eduvpn.app.service.ConnectionService;
import nl.eduvpn.app.service.HistoryService;
import nl.eduvpn.app.service.PreferencesService;
import nl.eduvpn.app.service.SerializerService;
import nl.eduvpn.app.utils.ErrorDialog;
import nl.eduvpn.app.utils.ItemClickSupport;
import nl.eduvpn.app.utils.Log;

/**
 * The fragment showing the provider list.
 * Created by Daniel Zolnai on 2016-10-07.
 */
public class ProviderSelectionFragment extends Fragment {

    private static final String TAG = ProviderSelectionFragment.class.getName();
    public static final String EXTRA_AUTHORIZATION_TYPE = "extra_authorization_type";

    @BindView(R.id.provider_list)
    protected RecyclerView _providerList;

    @BindView(R.id.provider_status)
    protected TextView _providerStatus;

    @BindView(R.id.header)
    protected TextView _header;

    @BindView(R.id.description)
    protected TextView _description;

    @Inject
    protected ConfigurationService _configurationService;

    @Inject
    protected APIService _apiService;

    @Inject
    protected SerializerService _serializerService;

    @Inject
    protected ConnectionService _connectionService;

    @Inject
    protected HistoryService _historyService;

    @Inject
    protected PreferencesService _preferencesService;

    private Unbinder _unbinder;

    @AuthorizationType
    private int _authorizationType;

    private RecyclerView.AdapterDataObserver _dataObserver;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            //noinspection WrongConstant
            _authorizationType = savedInstanceState.getInt(EXTRA_AUTHORIZATION_TYPE, -1);
            if (_authorizationType < 0) {
                throw new RuntimeException("Selected connection type was not saved into instance state!");
            }
        } else {
            //noinspection WrongConstant
            _authorizationType = getArguments().getInt(EXTRA_AUTHORIZATION_TYPE, -1);
            if (_authorizationType < 0) {
                throw new RuntimeException("Selected connection type was not provided via arguments!");
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_AUTHORIZATION_TYPE, _authorizationType);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_provider_selection, container, false);
        _unbinder = ButterKnife.bind(this, view);
        EduVPNApplication.get(view.getContext()).component().inject(this);
        if (_authorizationType == AuthorizationType.LOCAL) {
            _header.setText(R.string.select_your_institution_title);
            _description.setVisibility(View.GONE);
        } else {
            _header.setText(R.string.select_your_country_title);
            _description.setVisibility(View.VISIBLE);
        }
        _providerList.setHasFixedSize(true);
        _providerList.setLayoutManager(new LinearLayoutManager(view.getContext(), LinearLayoutManager.VERTICAL, false));
        final ProviderAdapter adapter = new ProviderAdapter(_configurationService, _authorizationType);
        _providerList.setAdapter(adapter);
        ItemClickSupport.addTo(_providerList).setOnItemClickListener(new ItemClickSupport.OnItemClickListener() {
            @Override
            public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                Instance instance = ((ProviderAdapter)recyclerView.getAdapter()).getItem(position);
                if (instance == null) {
                    // Should never happen
                    View mainView = getView();
                    if (mainView != null) {
                        Snackbar.make(mainView, R.string.error_selected_instance_not_found, Snackbar.LENGTH_LONG).show();
                    }
                    Log.e(TAG, "Instance not found for position: " + String.valueOf(position));
                } else {
                    _connectToApi(instance);
                }
            }
        });
        // When clicked long on an item, display its name in a toast.
        ItemClickSupport.addTo(_providerList).setOnItemLongClickListener(new ItemClickSupport.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClicked(RecyclerView recyclerView, int position, View v) {
                Instance instance = ((ProviderAdapter)recyclerView.getAdapter()).getItem(position);
                String name = instance == null ? getString(R.string.display_other_name) : instance.getDisplayName();
                Toast.makeText(recyclerView.getContext(), name, Toast.LENGTH_LONG).show();
                return true;
            }
        });
        adapter.registerAdapterDataObserver(_dataObserver = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                if (adapter.getItemCount() > 0) {
                    _providerStatus.setVisibility(View.GONE);
                } else if (adapter.isDiscoveryPending()){
                    _providerStatus.setText(R.string.discovering_providers);
                    _providerStatus.setVisibility(View.VISIBLE);
                } else {
                    _providerStatus.setText(R.string.no_provider_found);
                    _providerStatus.setVisibility(View.VISIBLE);
                }
            }
        });
        // Trigger initial status
        _dataObserver.onChanged();
        return view;
    }

    /**
     * Starts connecting to an API provider.
     *
     * @param instance The instance to connect to.
     */
    private void _connectToApi(final Instance instance) {
        DiscoveredAPI discoveredAPI = _historyService.getCachedDiscoveredAPI(instance.getSanitizedBaseURI());
        // If there's only a discovered API, initiate the connection
        if (discoveredAPI != null) {
            Log.d(TAG, "Cached discovered API found.");
            _connectionService.initiateConnection(getActivity(), instance, discoveredAPI);
            return;
        }
        // If no discovered API, fetch it first, then initiate the connection for the login
        Log.d(TAG, "No cached discovered API found, continuing with discovery.");
        final ProgressDialog dialog = ProgressDialog.show(getContext(), getString(R.string.progress_dialog_title), getString(R.string.api_discovery_message), true);
        // Discover the API
        _apiService.getJSON(instance.getSanitizedBaseURI() + Constants.API_DISCOVERY_POSTFIX, null, new APIService.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    DiscoveredAPI discoveredAPI = _serializerService.deserializeDiscoveredAPI(result);
                    dialog.dismiss();
                    // Cache the result
                    _historyService.cacheDiscoveredAPI(instance.getSanitizedBaseURI(), discoveredAPI);
                    _connectionService.initiateConnection(getActivity(), instance, discoveredAPI);
                } catch (SerializerService.UnknownFormatException ex) {
                    Log.e(TAG, "Error parsing discovered API!", ex);
                    ErrorDialog.show(getContext(), R.string.error_dialog_title, ex.toString());
                    dialog.dismiss();
                }
            }

            @Override
            public void onError(String errorMessage) {
                dialog.dismiss();
                Log.e(TAG, "Error while fetching discovered API: " + errorMessage);
                ErrorDialog.show(getContext(), R.string.error_dialog_title, errorMessage);
            }
        });
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (_providerList != null && _providerList.getAdapter() != null && _dataObserver != null) {
            _providerList.getAdapter().unregisterAdapterDataObserver(_dataObserver);
            _dataObserver = null;
        }
        _unbinder.unbind();
    }
}
