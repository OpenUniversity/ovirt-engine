package org.ovirt.engine.api.restapi.resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.ws.rs.core.Response;

import org.ovirt.engine.api.resource.LabelsResource;
import org.ovirt.engine.core.common.action.LabelNicParameters;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.businessentities.network.HostNicVfsConfig;
import org.ovirt.engine.core.common.businessentities.network.pseudo.NetworkLabel;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.linq.Function;
import org.ovirt.engine.core.utils.linq.LinqUtils;

public class BackendVirtualFunctionAllowedLabelsResource extends AbstractBaseHostNicLabelsResource
        implements LabelsResource {

    private Guid nicId;
    private String hostId;

    protected BackendVirtualFunctionAllowedLabelsResource(Guid nicId, String hostId) {
        super(nicId, hostId);

        this.nicId = nicId;
        this.hostId = hostId;
    }

    @Override
    protected List<NetworkLabel> getHostNicLabels(Guid hostNicId) {
        final BackendHostNicsResource hostNicsResource = inject(new BackendHostNicsResource(hostId));
        final HostNicVfsConfig vfsConfig = hostNicsResource.findVfsConfig(hostNicId);
        if (vfsConfig == null) {
            return Collections.emptyList();
        }
        final Set<String> networkLabelIds = vfsConfig.getNetworkLabels();
        final List<NetworkLabel> networkLabels =
                LinqUtils.transformToList(networkLabelIds, new Function<String, NetworkLabel>() {
                    @Override
                    public NetworkLabel eval(String labelId) {
                        return new NetworkLabel(labelId);
                    }
                });
        return networkLabels;
    }

    @Override
    protected Response performCreate(String labelId) {
        return performCreate(VdcActionType.AddVfsConfigLabel,
                new LabelNicParameters(nicId, labelId),
                new NetworkLabelIdResolver(nicId));
    }

    @Override
    protected AbstractBaseHostNicLabelResource createSingularResource(String labelId) {
        return new BackendVirtualFunctionAllowedLabelResource(labelId, this);
    }

}
