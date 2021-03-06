package org.ovirt.engine.core.bll;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.job.ExecutionHandler;
import org.ovirt.engine.core.bll.scheduling.VdsFreeMemoryChecker;
import org.ovirt.engine.core.bll.snapshots.SnapshotsValidator;
import org.ovirt.engine.core.bll.storage.disk.image.ImagesHandler;
import org.ovirt.engine.core.bll.validator.VmValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskImagesValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.FeatureSupported;
import org.ovirt.engine.core.common.action.ChangeVMClusterParameters;
import org.ovirt.engine.core.common.action.MigrateVmParameters;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.businessentities.MigrationMethod;
import org.ovirt.engine.core.common.businessentities.MigrationSupport;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.VmPauseStatus;
import org.ovirt.engine.core.common.businessentities.network.InterfaceStatus;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.businessentities.network.VdsNetworkInterface;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineError;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.vdscommands.MigrateStatusVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.MigrateVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.NetworkUtils;

@NonTransactiveCommandAttribute
public class MigrateVmCommand<T extends MigrateVmParameters> extends RunVmCommandBase<T> {

    /** The VDS that the VM is going to migrate to */
    private VDS destinationVds;

    /** Used to log the migration error. */
    private EngineError migrationErrorCode;

    private Integer actualDowntime;

    public MigrateVmCommand(T parameters) {
        this(parameters, null);
    }

    public MigrateVmCommand(T migrateVmParameters, CommandContext cmdContext) {
        super(migrateVmParameters, cmdContext);

        if (migrateVmParameters.getTargetClusterId() != null) {
            setClusterId(migrateVmParameters.getTargetClusterId());
            // force reload
            setCluster(null);
        }
    }

    /**
     * this property is used for audit log events
     */
    public final String getDestinationVdsName() {
        VDS destinationVds = getDestinationVds();
        return destinationVds != null ? destinationVds.getName() : null;
    }

    /**
     * @return Migration error text which is used in audit log message, if the migration status was queried from VDSM.
     */
    public String getDueToMigrationError() {
        if (migrationErrorCode == null) {
            return " ";
        }

        return " due to Error: " + Backend.getInstance()
                .getVdsErrorsTranslator()
                .translateErrorTextSingle(migrationErrorCode.name(), true);
    }

    /**
     * Returns the VDS that the VM is about to migrate to
     */
    protected VDS getDestinationVds() {
        return destinationVds;
    }

    @Override
    protected void processVmOnDown() {
        // In case the migration failed and the VM turned back to Up in the
        // source, we don't need to handle it as a VM that failed to run
        if (getVm().getStatus() != VMStatus.Up) {
            super.processVmOnDown();
        }
    }

    protected boolean initVdss() {
        setVdsIdRef(getVm().getRunOnVds());
        Guid vdsToRunOn =
                schedulingManager.schedule(getCluster(),
                        getVm(),
                        getVdsBlackList(),
                        getVdsWhiteList(),
                        getDestinationHostList(),
                        new ArrayList<>(),
                        new VdsFreeMemoryChecker(this),
                        getCorrelationId());
        setDestinationVdsId(vdsToRunOn);
        if (vdsToRunOn != null && !Guid.Empty.equals(vdsToRunOn)) {
            getRunVdssList().add(vdsToRunOn);
        }
        VmHandler.updateVmGuestAgentVersion(getVm());

        if (vdsToRunOn != null && vdsToRunOn.equals(Guid.Empty)) {
            return false;
        }

        if (getDestinationVds() == null || getVds() == null) {
            return false;
        }

        return true;
    }

    private List<Guid> getDestinationHostList() {
        List<Guid> destinationHostGuidList = new LinkedList<>();
        if (getDestinationVdsId() != null){
            destinationHostGuidList.add(getDestinationVdsId());
        }
        return destinationHostGuidList;
    }

    @Override
    protected void executeVmCommand() {
        setSucceeded(initVdss() && perform());
    }

    private boolean perform() {
        getParameters().setStartTime(new Date());

        try {
            if (connectLunDisks(getDestinationVdsId()) && migrateVm()) {
                ExecutionHandler.setAsyncJob(getExecutionContext(), true);
                return true;
            }
        }
        catch (EngineException e) {
        }

        runningFailed();
        return false;
    }

    private boolean migrateVm() {
        setActionReturnValue(getVdsBroker()
                .runAsyncVdsCommand(
                        VDSCommandType.Migrate,
                        createMigrateVDSCommandParameters(),
                        this)
                .getReturnValue());

        return getActionReturnValue() == VMStatus.MigratingFrom;
    }

    private MigrateVDSCommandParameters createMigrateVDSCommandParameters() {
        String srcVdsHost = getVds().getHostName();
        String dstVdsHost = String.format("%1$s:%2$s",
                getDestinationVds().getHostName(),
                getDestinationVds().getPort());

        return new MigrateVDSCommandParameters(getVdsId(), getVmId(), srcVdsHost, getDestinationVdsId(),
                dstVdsHost, MigrationMethod.ONLINE, isTunnelMigrationUsed(), getMigrationNetworkIp(), getVds().getClusterCompatibilityVersion(),
                getMaximumMigrationDowntime(), getAutoConverge(), getMigrateCompressed(), getDestinationVds().getConsoleAddress());
    }

    @Override
    public void runningSucceded() {
        try {
            getDowntime();
            getVmDynamicDao().clearMigratingToVds(getVmId());
            updateVmAfterMigrationToDifferentCluster();
        }
        finally {
            super.runningSucceded();
        }
    }

    protected void getDowntime() {
        if (FeatureSupported.migrateDowntime(getVm().getCompatibilityVersion())) {
            try {
                VDSReturnValue retVal = runVdsCommand(VDSCommandType.MigrateStatus,
                        new MigrateStatusVDSCommandParameters(getDestinationVdsId(), getVmId()));
                if (retVal != null) {
                    actualDowntime = (Integer) retVal.getReturnValue();
                }
            } catch (EngineException e) {
                migrationErrorCode = e.getErrorCode();
            }
        }
    }

    private void updateVmAfterMigrationToDifferentCluster() {
        if (getVm().getClusterId().equals(getParameters().getTargetClusterId())) {
            return;
        }

        ChangeVMClusterParameters params = new ChangeVMClusterParameters(
                getParameters().getTargetClusterId(),
                getVmId(),
                getVm().getCustomCompatibilityVersion());
        setSucceeded(getBackend().runInternalAction(VdcActionType.ChangeVMCluster, params).getSucceeded());
    }

    private Boolean getAutoConverge() {
        if (FeatureSupported.autoConvergence(getVm().getCompatibilityVersion())) {
            if (getVm().getAutoConverge() != null) {
                return getVm().getAutoConverge();
            }

            if (getCluster().getAutoConverge() != null) {
                return getCluster().getAutoConverge();
            }

            return Config.getValue(ConfigValues.DefaultAutoConvergence);
        }

        return null;
    }

    private Boolean getMigrateCompressed() {
        if (FeatureSupported.migrationCompression(getVm().getCompatibilityVersion())) {
            if (getVm().getMigrateCompressed() != null) {
                return getVm().getMigrateCompressed();
            }

            if (getCluster().getMigrateCompressed() != null) {
                return getCluster().getMigrateCompressed();
            }

            return Config.getValue(ConfigValues.DefaultMigrationCompression);
        }

        return null;
    }

    private int getMaximumMigrationDowntime() {
        if (getVm().getMigrationDowntime() != null) {
            return getVm().getMigrationDowntime();
        }

        return Config.getValue(ConfigValues.DefaultMaximumMigrationDowntime);
    }

    private boolean isTunnelMigrationUsed() {
        if (!FeatureSupported.tunnelMigration(getVm().getCompatibilityVersion())) {
            return false;
        }
        // if vm has no override for tunnel migration (its null),
        // use cluster's setting
        return getVm().getTunnelMigration() != null ?
                getVm().getTunnelMigration()
                : getCluster().isTunnelMigration();
    }

    private String getMigrationNetworkIp() {

        if (!FeatureSupported.migrationNetwork(getVm().getCompatibilityVersion())) {
            return null;
        }

        Network migrationNetwork = null;

        // Find migrationNetworkCluster
        List<Network> allNetworksInCluster = getNetworkDao().getAllForCluster(getVm().getClusterId());

        for (Network tempNetwork : allNetworksInCluster) {
            if (tempNetwork.getCluster().isMigration()) {
                migrationNetwork = tempNetwork;
                break;
            }
        }

        if (migrationNetwork != null) {

            // assure migration network is active on source host
            if (getMigrationNetworkAddress(getVds().getId(), migrationNetwork.getName()) == null) {
                return null;
            }

            // find migration IP address on destination host
            return getMigrationNetworkAddress(getDestinationVds().getId(), migrationNetwork.getName());
        }

        return null;
    }

    private String getMigrationNetworkAddress(Guid hostId, String migrationNetworkName) {
        final List<VdsNetworkInterface> nics =
                getDbFacade().getInterfaceDao().getAllInterfacesForVds(hostId);

        for (VdsNetworkInterface nic : nics) {
            if (migrationNetworkName.equals(nic.getNetworkName()) && migrationInterfaceUp(nic, nics)) {
                return nic.getAddress();
            }
        }

        return null;
    }

    protected boolean migrationInterfaceUp(VdsNetworkInterface nic, List<VdsNetworkInterface> nics) {
        if (NetworkUtils.isVlan(nic)) {
            String physicalNic = nic.getBaseInterface();
            for (VdsNetworkInterface iface : nics) {
                if (iface.getName().equals(physicalNic)) {
                    return iface.getStatistics().getStatus() == InterfaceStatus.UP;
                }
            }
        }

        return nic.getStatistics().getStatus() == InterfaceStatus.UP;
    }

    /**
     * command succeeded and VM is up => migration done
     * command succeeded and VM is not up => migration started
     * command failed and rerun flag is set => rerun migration was initiated
     * command failed and rerun flag is not set => migration failed
     */
    @Override
    public AuditLogType getAuditLogTypeValue() {
        return AuditLogType.UNASSIGNED;
    }

    private AuditLogType getAuditLogForMigrationStarted() {
        return isInternalExecution() ?
                AuditLogType.VM_MIGRATION_START_SYSTEM_INITIATED
                : AuditLogType.VM_MIGRATION_START;
    }

    protected AuditLogType getAuditLogForMigrationFailure() {
        if (getVds().getStatus() == VDSStatus.PreparingForMaintenance) {
            return AuditLogType.VM_MIGRATION_FAILED_DURING_MOVE_TO_MAINTENANCE;
        }

        if (getDestinationVds() == null) {
            auditLogDirector.log(this, AuditLogType.VM_MIGRATION_NO_VDS_TO_MIGRATE_TO);
        }

        return AuditLogType.VM_MIGRATION_FAILED;
    }

    protected Guid getDestinationVdsId() {
        VDS destinationVds = getDestinationVds();
        return destinationVds != null ? destinationVds.getId() : null;
    }

    protected void setDestinationVdsId(Guid vdsId) {
        destinationVds = vdsId != null ? getVdsDao().get(vdsId) : null;
    }

    @Override
    protected boolean validate() {
        final VM vm = getVm();

        if (vm == null) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_NOT_FOUND);
        }

        if (!canRunActionOnNonManagedVm()) {
            return false;
        }

        VmValidator vmValidator = new VmValidator(vm);
        if (!validate(vmValidator.isVmPluggedDiskNotUsingScsiReservation())) {
            return false;
        }

        if (!FeatureSupported.isMigrationSupported(getCluster().getArchitecture(), getCluster().getCompatibilityVersion())) {
            return failValidation(EngineMessage.MIGRATION_IS_NOT_SUPPORTED);
        }

        // If VM is pinned to host, no migration can occur
        if (vm.getMigrationSupport() == MigrationSupport.PINNED_TO_HOST) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_IS_PINNED_TO_HOST);
        }

        if (vm.getMigrationSupport() == MigrationSupport.IMPLICITLY_NON_MIGRATABLE
                && !getParameters().isForceMigrationForNonMigratableVm()) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_IS_NON_MIGRTABLE_AND_IS_NOT_FORCED_BY_USER_TO_MIGRATE);
        }

        switch (vm.getStatus()) {
        case MigratingFrom:
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_MIGRATION_IN_PROGRESS);

        case NotResponding:
            return failVmStatusIllegal();

        case Paused:
            if (vm.getVmPauseStatus() == VmPauseStatus.EIO) {
                return failValidation(EngineMessage.MIGRATE_PAUSED_EIO_VM_IS_NOT_SUPPORTED);
            }
            break;

        default:
        }

        if (!vm.isQualifyToMigrate()) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_VM_IS_NOT_RUNNING);
        }

        if (!validate(vmValidator.vmNotHavingPluggedDiskSnapshots(EngineMessage.ACTION_TYPE_FAILED_VM_HAS_PLUGGED_DISK_SNAPSHOT))
                || !validate(vmValidator.vmNotHavingPassthroughVnics())) {
            return false;
        }

        if (getParameters().getTargetClusterId() != null) {
            ChangeVmClusterValidator changeVmClusterValidator = new ChangeVmClusterValidator(
                    this,
                    getParameters().getTargetClusterId(),
                    getVm().getCustomCompatibilityVersion());
            if (!changeVmClusterValidator.validate()) {
                return false;
            }
        }

        return validate(new SnapshotsValidator().vmNotDuringSnapshot(vm.getId()))
                // This check was added to prevent migration of VM while its disks are being migrated
                // TODO: replace it with a better solution
                && validate(new DiskImagesValidator(ImagesHandler.getPluggedActiveImagesForVm(vm.getId())).diskImagesNotLocked())
                && schedulingManager.canSchedule(getCluster(),
                        getVm(),
                        getVdsBlackList(),
                        getVdsWhiteList(),
                        getDestinationHostList(),
                        getReturnValue().getValidationMessages());
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__ACTION__MIGRATE);
        addValidationMessage(EngineMessage.VAR__TYPE__VM);
    }

    @Override
    public void rerun() {
         // make Vm property to null in order to refresh it from db
        setVm(null);

        determineMigrationFailureForAuditLog();

        // if vm is up and rerun is called then it got up on the source, try to rerun
        if (getVm() != null && getVm().getStatus() == VMStatus.Up) {
            super.rerun();
        } else {
            // vm went down on the destination and source, migration failed.
            runningFailed();
            // signal the caller that a rerun was made so that it won't log
            // the failure message again
            _isRerun = true;
        }
    }

    @Override
    protected void reexecuteCommand() {
        setDestinationVdsId(null);
        super.reexecuteCommand();
    }

    /**
     * Log that the migration had failed with the error code that is in the VDS and needs to be retrieved.
     */
    protected void determineMigrationFailureForAuditLog() {
        if (getVm() != null && getVm().getStatus() == VMStatus.Up) {
            try {
                runVdsCommand(VDSCommandType.MigrateStatus, new MigrateStatusVDSCommandParameters(getVdsId(), getVmId()));
            } catch (EngineException e) {
                migrationErrorCode = e.getErrorCode();
            }
        }
    }

    @Override
    protected Guid getCurrentVdsId() {
        Guid destinationVdsId = getDestinationVdsId();
        return destinationVdsId != null ? destinationVdsId : super.getCurrentVdsId();
    }

    // Duration: time that took for the actual migration
    public String getDuration() {
        return DurationFormatUtils.formatDurationWords(new Date().getTime() - getParameters().getStartTime().getTime(), true, true);
    }

    // TotalDuration: time that took migration including retries (can be identical to Duration)
    public String getTotalDuration() {
        return DurationFormatUtils.formatDurationWords(new Date().getTime() - getParameters().getTotalMigrationTime().getTime(), true, true);
    }

    // ActualDowntime: returns the actual time that the vm was offline (not available for access)
    public String getActualDowntime() {
        return (actualDowntime == null) ? "(N/A)" : actualDowntime + "ms";
    }

    // hosts that cannot be selected for scheduling (failed hosts + VM source host)
    private List<Guid> getVdsBlackList() {
        List<Guid> blackList = new ArrayList<>(getRunVdssList());
        if (getVdsId() != null) {
            blackList.add(getVdsId());
        }
        return blackList;
    }

    // initial hosts list picked for scheduling, currently
    // passed by load balancing process.
    protected List<Guid> getVdsWhiteList() {
        return getParameters().getInitialHosts();
    }

    @Override
    public void onPowerringUp() {
        // nothing to do
    }

    protected boolean isReturnValueUp() {
            return getActionReturnValue() == VMStatus.Up;
    }

    private boolean isHostInPrepareForMaintenance() {
            return getVds().getStatus() == VDSStatus.PreparingForMaintenance;
    }

    private boolean isClusterChange() {
            return getVm() != null && !Objects.equals(getParameters().getTargetClusterId(), getVm().getClusterId());
    }
}
