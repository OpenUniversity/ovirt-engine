package org.ovirt.engine.core.bll.network.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.bll.network.cluster.ManagementNetworkUtil;
import org.ovirt.engine.core.bll.network.cluster.NetworkClusterHelper;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.action.UpdateNetworkToVdsParameters;
import org.ovirt.engine.core.common.businessentities.Entities;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.businessentities.network.NetworkBootProtocol;
import org.ovirt.engine.core.common.businessentities.network.VdsNetworkInterface;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineFault;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.vdscommands.CollectHostNetworkDataVdsCommandParameters;
import org.ovirt.engine.core.common.vdscommands.NetworkVdsmVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.Dns;
import org.ovirt.engine.core.utils.IPAddress;
import org.ovirt.engine.core.utils.NetworkUtils;
import org.ovirt.engine.core.utils.threadpool.ThreadPoolUtil;

public class UpdateNetworkToVdsInterfaceCommand<T extends UpdateNetworkToVdsParameters> extends VdsNetworkCommand<T> {

    @Inject
    private ManagementNetworkUtil managementNetworkUtil;

    private List<VdsNetworkInterface> interfaces;
    private VDSReturnValue retVal;
    private boolean editNetworkDone = false;
    private boolean editNetworkThreadFinish = false;

    public UpdateNetworkToVdsInterfaceCommand(T parameters) {
        super(parameters);
    }

    @Override
    protected void executeCommand() {
        String address = getParameters().getAddress();
        String subnet = StringUtils.isEmpty(getParameters().getSubnet()) ? getParameters().getNetwork()
                .getSubnet() : getParameters().getSubnet();
        String gateway = StringUtils.isEmpty(getParameters().getGateway()) ? "" : getParameters().getGateway();
        ArrayList<String> interfaceNames = new ArrayList<>();

        Map<String, VdsNetworkInterface> interfaceByName = Entities.entitiesByName(interfaces);
        for (VdsNetworkInterface i : getParameters().getInterfaces()) {
            VdsNetworkInterface existingIface = interfaceByName.get(i.getName());
            if (Boolean.TRUE.equals(existingIface.getBonded()) || NetworkUtils.isBondVlan(interfaces, existingIface)) {
                getParameters().setBondName(NetworkUtils.stripVlan(existingIface));
                for (VdsNetworkInterface ix : interfaces) {
                    if (NetworkUtils.interfaceBasedOn(existingIface, ix.getBondName())) {
                        interfaceNames.add(NetworkUtils.stripVlan(ix));
                    }
                }
            } else {
                interfaceNames.add(NetworkUtils.stripVlan(existingIface));
            }
        }

        // updating a vlan over bond if the bond was not provided the bond device should preserve the bonding options
        String bondingOptions = null;
        if (getParameters().getBondingOptions() == null && getParameters().getBondName() != null
                && !Entities.entitiesByName(getParameters().getInterfaces()).containsKey(getParameters().getBondName())) {
            VdsNetworkInterface bond = Entities.entitiesByName(interfaces).get(getParameters().getBondName());
            bondingOptions = bond == null ? null : bond.getBondOptions();
        } else {
            bondingOptions = getParameters().getBondingOptions();
        }

        NetworkVdsmVDSCommandParameters parameters =
                new NetworkVdsmVDSCommandParameters(getParameters().getVdsId(),
                        getParameters().getNetwork().getName(),
                        getParameters().getNetwork().getVlanId(),
                        getParameters().getBondName(),
                        interfaceNames.toArray(new String[] {}),
                        address,
                        subnet,
                        gateway,
                        getParameters().getNetwork().getStp(),
                        bondingOptions,
                        getParameters().getBootProtocol());
        parameters.setVmNetwork(getParameters().getNetwork().isVmNetwork());
        parameters.setOldNetworkName(getParameters().getOldNetworkName());
        parameters.setConnectionTimeout(Config.<Integer> getValue(ConfigValues.NetworkConnectivityCheckTimeoutInSeconds));
        parameters.setCheckConnectivity(getParameters().getCheckConnectivity());

        IPAddress[] adresses = Dns.getHostAddresses(NetworkUtils.OS_REFERENCE_TO_MACHINE_NAME);
        if (adresses != null && adresses.length > 0) {
            parameters.setHostAddr(adresses[0].toString());
        }

        if (getParameters().getCheckConnectivity()) {
            ThreadPoolUtil.execute(new EditNetworkThread(parameters));
            pollVds(getVds());
        } else {
            editNetworkThreadCompat(parameters);
        }

        if (retVal != null && editNetworkDone) {
            // update vds network data
            retVal = runVdsCommand(VDSCommandType.CollectVdsNetworkData,
                            new CollectHostNetworkDataVdsCommandParameters(getVds()));

            if (retVal.getSucceeded()) {
                Guid groupId = getVdsDao().get(getParameters().getVdsId()).getClusterId();
                NetworkClusterHelper.setStatus(groupId, getParameters().getNetwork());
                setSucceeded(true);
            }
        }
    }

    private class EditNetworkThread implements Runnable {
        private final NetworkVdsmVDSCommandParameters parameters;

        public EditNetworkThread(NetworkVdsmVDSCommandParameters parameters) {
            this.parameters = parameters;
        }

        @Override
        public void run() {
            editNetworkThreadCompat(parameters);
        }
    }

    private void editNetworkThreadCompat(NetworkVdsmVDSCommandParameters parameters) {
        try {
            retVal = runVdsCommand(VDSCommandType.EditNetwork, parameters);
            editNetworkDone = true;
        } catch (RuntimeException e) {
            if (e instanceof EngineException) {
                getReturnValue().setFault(new EngineFault(e, ((EngineException) e).getVdsError().getCode()));
            }
        } catch (Exception e) {
        } finally {
            editNetworkThreadFinish = true;
        }
    }

    protected void pollVds(VDS vds) {
        int retries = 10;
        while (retries > 0 && retVal == null && !editNetworkThreadFinish) {
            retries--;
            try {
                runVdsCommand(VDSCommandType.CollectVdsNetworkData,
                                new CollectHostNetworkDataVdsCommandParameters(vds));
            } catch (Exception e) {
            }
        }
    }

    @Override
    protected boolean validate() {
        String ifaceGateway = null;
        interfaces = getDbFacade().getInterfaceDao().getAllInterfacesForVds(getParameters().getVdsId());

        // check that interface exists
        for (final VdsNetworkInterface i : getParameters().getInterfaces()) {
            VdsNetworkInterface iface =
                    interfaces.stream().filter(x -> x.getName().equals(i.getName())).findFirst().orElse(null);
            if (iface == null) {
                addValidationMessage(EngineMessage.NETWORK_INTERFACE_NOT_EXISTS);
                return false;
            }
            ifaceGateway = iface.getGateway();
        }

        // check that the old network name is not null
        if (StringUtils.isEmpty(getParameters().getOldNetworkName())) {
            addValidationMessage(EngineMessage.NETWORK_OLD_NETWORK_NOT_SPECIFIED);
            return false;
        }

        VDS vds = getVdsDao().get(getParameters().getVdsId());
        if (vds.getStatus() != VDSStatus.Maintenance) {
            // check that the old network exists in host
            boolean ifaceFound = interfaces.stream()
                    .anyMatch(i -> i.getNetworkName() != null && i.getNetworkName().equals(getParameters().getNetwork().getName()));
            if (ifaceFound) {
                addValidationMessage(EngineMessage.NETWORK_HOST_IS_BUSY);
                return false;
            }
        }

        // check that the old network exists in host
        boolean ifacenetNotFound = interfaces.stream()
                .noneMatch(i -> i.getNetworkName() != null && i.getNetworkName().equals(getParameters().getOldNetworkName()));
        if (ifacenetNotFound) {
            addValidationMessage(EngineMessage.NETWORK_NOT_EXISTS);
            return false;
        }

        final Guid clusterId = getClusterId();
        if (!managementNetworkUtil.isManagementNetwork(getParameters().getNetwork().getName(), clusterId)) {
            if (managementNetworkUtil.isManagementNetwork(getParameters().getOldNetworkName(), clusterId)) {
                getReturnValue().getValidationMessages()
                        .add(EngineMessage.NETWORK_DEFAULT_UPDATE_NAME_INVALID.toString());
                getReturnValue().getValidationMessages()
                        .add(String.format("$NetworkName %1$s", getParameters().getOldNetworkName()));
                return false;
            }

            if (StringUtils.isNotEmpty(getParameters().getGateway())) {
                if (!getParameters().getGateway().equals(ifaceGateway)) {
                    addValidationMessage(EngineMessage.NETWORK_ATTACH_ILLEGAL_GATEWAY);
                    return false;
                }
                // if the gateway didn't change we don't want the vdsm to set it.
                else {
                    getParameters().setGateway(null);
                }
            }

            // check connectivity
            if (getParameters().getCheckConnectivity()) {
                addValidationMessage(EngineMessage.NETWORK_CHECK_CONNECTIVITY);
                return false;
            }
        }

        // check address exists in static ip
        if (getParameters().getBootProtocol() == NetworkBootProtocol.STATIC_IP) {
            if (StringUtils.isEmpty(getParameters().getAddress())) {
                addValidationMessage(EngineMessage.NETWORK_ADDR_MANDATORY_IN_STATIC_IP);
                return false;
            }
        }

        Network network = getNetworkDao().getByNameAndCluster(getNetworkName(), vds.getClusterId());
        if (network != null && network.isExternal()) {
            return failValidation(EngineMessage.EXTERNAL_NETWORK_CANNOT_BE_PROVISIONED);
        }

        return true;
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return getSucceeded() ? AuditLogType.NETWORK_ATTACH_NETWORK_TO_VDS
                : AuditLogType.NETWORK_ATTACH_NETWORK_TO_VDS_FAILED;
    }
}
