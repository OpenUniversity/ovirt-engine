package org.ovirt.engine.core.vdsbroker;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.businessentities.OriginType;
import org.ovirt.engine.core.common.businessentities.UnchangeableByVdsm;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.businessentities.VmBalloonInfo;
import org.ovirt.engine.core.common.businessentities.VmDynamic;
import org.ovirt.engine.core.common.businessentities.VmExitReason;
import org.ovirt.engine.core.common.businessentities.VmExitStatus;
import org.ovirt.engine.core.common.businessentities.VmGuestAgentInterface;
import org.ovirt.engine.core.common.businessentities.VmJob;
import org.ovirt.engine.core.common.businessentities.VmNumaNode;
import org.ovirt.engine.core.common.businessentities.VmPauseStatus;
import org.ovirt.engine.core.common.businessentities.VmStatistics;
import org.ovirt.engine.core.common.businessentities.network.VmNetworkInterface;
import org.ovirt.engine.core.common.businessentities.network.VmNetworkStatistics;
import org.ovirt.engine.core.common.businessentities.storage.Disk;
import org.ovirt.engine.core.common.businessentities.storage.DiskImageDynamic;
import org.ovirt.engine.core.common.businessentities.storage.DiskStorageType;
import org.ovirt.engine.core.common.businessentities.storage.LUNs;
import org.ovirt.engine.core.common.businessentities.storage.LunDisk;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.vdscommands.DestroyVmVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableBase;
import org.ovirt.engine.core.utils.NumaUtils;
import org.ovirt.engine.core.utils.ObjectIdentityChecker;
import org.ovirt.engine.core.vdsbroker.vdsbroker.entities.VmInternalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible of comparing 2 views of the same VM, one from DB and other as reported from VDSM, run checks, see what changed
 * and record what's changed in its internal state.
 */
public class VmAnalyzer {

    private VM dbVm;
    private final VmInternalData vdsmVm;

    private static final Map<Guid, Integer> vmsWithBalloonDriverProblem = new HashMap<>();
    private static final Map<Guid, Integer> vmsWithUncontrolledBalloon = new HashMap<>();

    private VmDynamic vmDynamicToSave;
    private boolean saveStatistics;
    private boolean saveVmInterfaces;
    private boolean movedToDown;
    private boolean rerun;
    private boolean clientIpChanged;
    private boolean poweringUp;
    private boolean succeededToRun;
    private boolean removeFromAsync;
    private boolean stable;
    private boolean autoVmToRun;
    private boolean externalVm;
    private boolean hostedEngineUnmanaged;
    private boolean coldRebootVmToRun;
    private Map<Guid, VmJob> vmJobsToUpdate;
    private List<Guid> vmJobIdsToRemove;
    private Collection<Pair<Guid, DiskImageDynamic>> vmDiskImageDynamicToSave;
    private List<LUNs> vmLunDisksToSave;
    private List<VmGuestAgentInterface> vmGuestAgentNics;

    //dependencies
    private final VmsMonitoring vmsMonitoring; // aggregate all data using it.

    private static final int TO_MEGA_BYTES = 1024;
    /** names of fields in {@link org.ovirt.engine.core.common.businessentities.VmDynamic} that are not changed by VDSM */
    private static final List<String> UNCHANGEABLE_FIELDS_BY_VDSM;
    private static final Logger log = LoggerFactory.getLogger(VmAnalyzer.class);

    static {
        List<String> tmpList = new ArrayList<>();
        for (Field field : VmDynamic.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(UnchangeableByVdsm.class)) {
                tmpList.add(field.getName());
            }
        }
        UNCHANGEABLE_FIELDS_BY_VDSM = Collections.unmodifiableList(tmpList);
    }

    private final AuditLogDirector auditLogDirector;

    // FIXME auditlogger is a dependency and realy doesn't belong in the constructor but
    // there is no way to mock it otherwise.
    public VmAnalyzer(VM dbVm, VmInternalData vdsmVm, VmsMonitoring vmsMonitoring, AuditLogDirector auditLogDirector) {
        this.dbVm = dbVm;
        this.vdsmVm = vdsmVm;
        this.vmsMonitoring = vmsMonitoring;
        this.auditLogDirector = auditLogDirector;
    }

    /**
     * update the internals of this VM
     * against its match
     * this method shouldn't throw exception but fail in isolated way.
     * TODO consider throwing a checked exception or catching one inside
     */
    protected void analyze() {
        if (vdsmVm == null) {
            removeVmFromCache();
            return;
        }

        if (isUnmanagedVm()) {
            saveDynamic(vdsmVm.getVmDynamic());
            return;
        }

        proceedDownVms();
        proceedWatchdogEvents();
        proceedBalloonCheck();
        proceedGuaranteedMemoryCheck();
        updateRepository();
        prepareGuestAgentNetworkDevicesForUpdate();
        updateLunDisks();
        updateVmJobs();
    }

    private boolean isUnmanagedVm() {
        externalVm = dbVm == null && getDbFacade().getVmStaticDao().get(vdsmVm.getVmDynamic().getId()) == null;
        hostedEngineUnmanaged = dbVm != null && dbVm.getOrigin() == OriginType.HOSTED_ENGINE;
        return externalVm || hostedEngineUnmanaged;
    }

    /**
     * Delete all vms with status Down
     */
    void proceedDownVms() {
        if (vdsmVm.getVmDynamic().getStatus() == VMStatus.Down) {
            VMStatus prevStatus = VMStatus.Unassigned;
            if (dbVm != null) {
                prevStatus = dbVm.getStatus();
                proceedVmBeforeDeletion();

                // when going to suspend, delete vm from cache later
                if (prevStatus == VMStatus.SavingState) {
                    vmsMonitoring.getResourceManager().internalSetVmStatus(dbVm, VMStatus.Suspended);

                }

                clearVm(vdsmVm.getVmDynamic().getExitStatus(),
                        vdsmVm.getVmDynamic().getExitMessage(),
                        vdsmVm.getVmDynamic().getExitReason());
            } else {
                VmDynamic dynamicFromDb = getDbFacade().getVmDynamicDao().get(vdsmVm.getVmDynamic().getId());
                if (dynamicFromDb != null) {
                    prevStatus = dynamicFromDb.getStatus();
                }
            }
            if (prevStatus != VMStatus.Unassigned) {
                vmsMonitoring.getResourceManager().runVdsCommand(
                        VDSCommandType.Destroy,
                        new DestroyVmVDSCommandParameters(
                                getVdsManager().getVdsId(),
                                vdsmVm.getVmDynamic().getId(),
                                null,
                                false,
                                false,
                                0,
                                true));

                if (dbVm != null && prevStatus == VMStatus.SavingState) {
                    afterSuspendTreatment(vdsmVm.getVmDynamic());
                } else if (prevStatus != VMStatus.MigratingFrom) {
                    handleVmOnDown(dbVm, vdsmVm.getVmDynamic());
                }
            }
        }
    }

    private void handleVmOnDown(VM cacheVm, VmDynamic vmDynamic) {
        VmExitStatus exitStatus = vmDynamic.getExitStatus();

        // we don't need to have an audit log for the case where the VM went down on a host
        // which is different than the one it should be running on (must be in migration process)
        if (cacheVm != null) {
            auditVmOnDownEvent(exitStatus, vmDynamic.getExitMessage(), vmDynamic.getId());
        }

        if (exitStatus != VmExitStatus.Normal) {
            // Vm failed to run - try to rerun it on other Vds
            if (cacheVm != null) {
                if (vmsMonitoring.getResourceManager().isVmInAsyncRunningList(vmDynamic.getId())) {
                    log.info("Running on vds during rerun failed vm: '{}'", vmDynamic.getRunOnVds());
                    rerun = true;
                } else if (cacheVm.isAutoStartup()) {
                    autoVmToRun = true;
                }
            }
            // if failed in destination right after migration
            else { // => cacheVm == null
                ResourceManager.getInstance().removeAsyncRunningVm(vmDynamic.getId());
                saveDynamic(vdsmVm.getVmDynamic());
            }
        } else {
            // Vm moved safely to down status. May be migration - just remove it from Async Running command.
            vmsMonitoring.getResourceManager().removeAsyncRunningVm(vmDynamic.getId());
            if (getVmManager() != null && getVmManager().isColdReboot()) {
                setColdRebootFlag();
            }
        }
    }

    private void saveDynamic(VmDynamic vmDynamic) {
        vmDynamicToSave = vmDynamic;
    }

    /**
     * Generate an error or information event according to the exit status of a VM in status 'down'
     */
    private void auditVmOnDownEvent(VmExitStatus exitStatus, String exitMessage, Guid vmStatisticsId) {
        AuditLogType type = exitStatus == VmExitStatus.Normal ? AuditLogType.VM_DOWN : AuditLogType.VM_DOWN_ERROR;
        AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), vmStatisticsId);
        if (exitMessage != null) {
            logable.addCustomValue("ExitMessage", "Exit message: " + exitMessage);
        }
        auditLog(logable, type);
    }

    private void afterSuspendTreatment(VmDynamic vm) {
        AuditLogType type = vm.getExitStatus() == VmExitStatus.Normal ? AuditLogType.USER_SUSPEND_VM_OK
                : AuditLogType.USER_FAILED_SUSPEND_VM;

        AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), vm.getId());
        auditLog(logable, type);
        ResourceManager.getInstance().removeAsyncRunningVm(vm.getId());
    }

    private void clearVm(VmExitStatus exitStatus, String exitMessage, VmExitReason vmExistReason) {
        if (dbVm.getStatus() != VMStatus.MigratingFrom) {
            // we must check that vm.getStatus() != VMStatus.Down because if it was set to down
            // the exit status and message were set, and we don't want to override them here.
            // we will add it to vmDynamicToSave though because it might been removed from it in #updateRepository
            if (dbVm.getStatus() != VMStatus.Suspended && dbVm.getStatus() != VMStatus.Down) {
                vmsMonitoring.getResourceManager().internalSetVmStatus(dbVm,
                        VMStatus.Down,
                        exitStatus,
                        exitMessage,
                        vmExistReason);
            }
            saveDynamic(dbVm.getDynamicData());
            saveStatistics();
            saveVmInterfaces();
            if (!vmsMonitoring.getResourceManager().isVmInAsyncRunningList(dbVm.getId())) {
                movedToDown = true;
            }
        }
    }

    private void saveStatistics() {
        saveStatistics = true;
    }

    public VmStatistics getVmStatisticsToSave() {
        return saveStatistics ? dbVm.getStatisticsData() : null;
    }

    public VmDynamic getVmDynamicToSave() {
        return vmDynamicToSave;
    }

    public List<VmNetworkStatistics> getVmNetworkStatistics() {
        return saveVmInterfaces ?
                dbVm.getInterfaces().stream().map(VmNetworkInterface::getStatistics).collect(Collectors.toList())
                : Collections.emptyList();
    }

    // TODO Method with Side-Effect - move to VmsMonitoring
    // switch command execution with state change and let a final execution point at #VmsMonitoring crate tasks out of the new state. this can be delegated to some task Q instead of running in-thread
    private void proceedVmBeforeDeletion() {
        AuditLogType type = AuditLogType.UNASSIGNED;
        AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), dbVm.getId());
        if (dbVm.getStatus() == VMStatus.MigratingFrom) {
            // if a VM that was a source host in migration process is now down with normal
            // exit status that's OK, otherwise..
            if (vdsmVm != null && vdsmVm.getVmDynamic() != null && vdsmVm.getVmDynamic().getExitStatus() != VmExitStatus.Normal) {
                if (dbVm.getMigratingToVds() != null) {
                    VDSReturnValue destoryReturnValue = vmsMonitoring.getResourceManager().runVdsCommand(
                            VDSCommandType.DestroyVm,
                            new DestroyVmVDSCommandParameters(new Guid(dbVm.getMigratingToVds().toString()),
                                    dbVm.getId(),
                                    true,
                                    false,
                                    0));
                    if (destoryReturnValue.getSucceeded()) {
                        log.info("Stopped migrating VM: '{}' on VDS: '{}'", dbVm.getName(),
                                dbVm.getMigratingToVds());
                    } else {
                        log.info("Could not stop migrating VM: '{}' on VDS: '{}', Error: '{}'", dbVm.getName(),
                                dbVm.getMigratingToVds(), destoryReturnValue.getExceptionString());
                    }
                }
                // set vm status to down if source vm crushed
                ResourceManager.getInstance().internalSetVmStatus(dbVm,
                        VMStatus.Down,
                        vdsmVm.getVmDynamic().getExitStatus(),
                        vdsmVm.getVmDynamic().getExitMessage(),
                        vdsmVm.getVmDynamic().getExitReason());
                saveDynamic(dbVm.getDynamicData());
                saveStatistics();
                saveVmInterfaces();
                type = AuditLogType.VM_MIGRATION_ABORT;
                logable.addCustomValue("MigrationError", vdsmVm.getVmDynamic().getExitMessage());

                vmsMonitoring.getResourceManager().removeAsyncRunningVm(vdsmVm.getVmDynamic().getId());
            }
        }

        if (type != AuditLogType.UNASSIGNED) {
            auditLog(logable, type);
        }
    }

    private void proceedWatchdogEvents() {
        VmDynamic vmDynamic = vdsmVm.getVmDynamic();
        VM vmTo = dbVm;
        if (isNewWatchdogEvent(vmDynamic, vmTo)) {
            AuditLogableBase auditLogable = new AuditLogableBase();
            auditLogable.setVmId(vmDynamic.getId());
            auditLogable.addCustomValue("wdaction", vmDynamic.getLastWatchdogAction());
            // for the interpretation of vdsm's response see http://docs.python.org/2/library/time.html
            auditLogable.addCustomValue("wdevent", new Date(vmDynamic.getLastWatchdogEvent() * 1000).toString());
            auditLog(auditLogable, AuditLogType.WATCHDOG_EVENT);
        }
    }

    protected static boolean isNewWatchdogEvent(VmDynamic vmDynamic, VM vmTo) {
        Long lastWatchdogEvent = vmDynamic.getLastWatchdogEvent();
        return vmTo != null && lastWatchdogEvent != null
                && (vmTo.getLastWatchdogEvent() == null || vmTo.getLastWatchdogEvent() < lastWatchdogEvent);
    }

    private void proceedBalloonCheck() {
        if (getVdsManager().getCopyVds().isBalloonEnabled()) {
            if (dbVm == null) {
                return;
            }
            VmBalloonInfo balloonInfo = vdsmVm.getVmStatistics().getVmBalloonInfo();
            if (balloonInfo == null) {
                return;
            }
            Guid vmId = vdsmVm.getVmDynamic().getId();
            /* last memory is null the first time we check it or when
               we're not getting the balloon info from vdsm
            */
            if (balloonInfo.getBalloonLastMemory() == null || balloonInfo.getBalloonLastMemory() == 0) {
                balloonInfo.setBalloonLastMemory(balloonInfo.getCurrentMemory());
                return;
            }

            if (isBalloonDeviceActiveOnVm(vdsmVm)
                    && (Objects.equals(balloonInfo.getCurrentMemory(), balloonInfo.getBalloonMaxMemory())
                    || !isBalloonWorking(balloonInfo))) {
                vmBalloonDriverIsRequestedAndUnavailable(vmId);
            } else {
                vmBalloonDriverIsNotRequestedOrAvailable(vmId);
            }

            // save the current value for the next time we check it
            balloonInfo.setBalloonLastMemory(balloonInfo.getCurrentMemory());

            if (vdsmVm.getVmStatistics().getUsageMemPercent() != null
                    && vdsmVm.getVmStatistics().getUsageMemPercent() == 0  // guest agent is down
                    && balloonInfo.isBalloonDeviceEnabled() // check if the device is present
                    && !Objects.equals(balloonInfo.getCurrentMemory(), balloonInfo.getBalloonMaxMemory())) {
                guestAgentIsDownAndBalloonInfalted(vmId);
            } else {
                guestAgentIsUpOrBalloonDeflated(vmId);
            }
        }
    }

    private boolean isBalloonDeviceActiveOnVm(VmInternalData vmInternalData) {
        VmBalloonInfo balloonInfo = vmInternalData.getVmStatistics().getVmBalloonInfo();
        return dbVm.getMinAllocatedMem() < dbVm.getMemSizeMb() // minimum allocated mem of VM == total mem, ballooning is impossible
                && balloonInfo.isBalloonDeviceEnabled()
                && balloonInfo.getBalloonTargetMemory().intValue() != balloonInfo.getBalloonMaxMemory().intValue(); // ballooning was not requested/enabled on this VM
    }

    // remove the vm from the list of vms with uncontrolled inflated balloon
    private void guestAgentIsUpOrBalloonDeflated(Guid vmId) {
        vmsWithUncontrolledBalloon.remove(vmId);
    }

    // add the vm to the list of vms with uncontrolled inflated balloon or increment its counter
    // if it is already in the list
    private void guestAgentIsDownAndBalloonInfalted(Guid vmId) {
        Integer currentVal = vmsWithUncontrolledBalloon.get(vmId);
        if (currentVal == null) {
            vmsWithUncontrolledBalloon.put(vmId, 1);
        } else {
            vmsWithUncontrolledBalloon.put(vmId, currentVal + 1);
            if (currentVal >= Config.<Integer> getValue(ConfigValues.IterationsWithBalloonProblem)) {
                AuditLogableBase auditLogable = new AuditLogableBase();
                auditLogable.setVmId(vmId);
                auditLogDirector.log(auditLogable, AuditLogType.VM_BALLOON_DRIVER_UNCONTROLLED);
                vmsWithUncontrolledBalloon.put(vmId, 0);
            }
        }
    }

    // remove the vm from the list of vms with balloon driver problem
    private void vmBalloonDriverIsNotRequestedOrAvailable(Guid vmId) {
        vmsWithBalloonDriverProblem.remove(vmId);
    }

    // add the vm to the list of vms with balloon driver problem or increment its counter
    // if it is already in the list
    private void vmBalloonDriverIsRequestedAndUnavailable(Guid vmId) {
        Integer currentVal = vmsWithBalloonDriverProblem.get(vmId);
        if (currentVal == null) {
            vmsWithBalloonDriverProblem.put(vmId, 1);
        } else {
            vmsWithBalloonDriverProblem.put(vmId, currentVal + 1);
            if (currentVal >= Config.<Integer> getValue(ConfigValues.IterationsWithBalloonProblem)) {
                AuditLogableBase auditLogable = new AuditLogableBase();
                auditLogable.setVmId(vmId);
                auditLogDirector.log(auditLogable, AuditLogType.VM_BALLOON_DRIVER_ERROR);
                vmsWithBalloonDriverProblem.put(vmId, 0);
            }
        }
    }

    private void proceedGuaranteedMemoryCheck() {
        if (dbVm != null) {
            VmStatistics vmStatistics = vdsmVm.getVmStatistics();
            if (vmStatistics != null && vmStatistics.getVmBalloonInfo() != null &&
                    vmStatistics.getVmBalloonInfo().getCurrentMemory() != null &&
                    vmStatistics.getVmBalloonInfo().getCurrentMemory() > 0 &&
                    dbVm.getMinAllocatedMem() > vmStatistics.getVmBalloonInfo().getCurrentMemory() / TO_MEGA_BYTES) {
                AuditLogableBase auditLogable = new AuditLogableBase();
                auditLogable.addCustomValue("VmName", dbVm.getName());
                auditLogable.addCustomValue("VdsName", this.getVdsManager().getVdsName());
                auditLogable.addCustomValue("MemGuaranteed", String.valueOf(dbVm.getMinAllocatedMem()));
                auditLogable.addCustomValue("MemActual",
                        Long.toString(vmStatistics.getVmBalloonInfo().getCurrentMemory() / TO_MEGA_BYTES));
                auditLog(auditLogable, AuditLogType.VM_MEMORY_UNDER_GUARANTEED_VALUE);
            }
        }
    }


    private void updateRepository() {
        VmDynamic vdsmVmDynamic = vdsmVm.getVmDynamic();

        // if not migrating here and not down
        if (!inMigrationTo(vdsmVmDynamic, dbVm) && vdsmVmDynamic.getStatus() != VMStatus.Down) {
            if (dbVm != null) {
                if (!StringUtils.equals(vdsmVmDynamic.getClientIp(), dbVm.getClientIp())) {
                    clientIpChanged = true;
                }
            }
            if (dbVm != null) {
                logVmStatusTransition();

                if (dbVm.getStatus() != VMStatus.Up && vdsmVmDynamic.getStatus() == VMStatus.Up
                        || dbVm.getStatus() != VMStatus.PoweringUp
                        && vdsmVmDynamic.getStatus() == VMStatus.PoweringUp) {
                    poweringUp = true;
                }

                // Generate an event for those machines that transition from "PoweringDown" to
                // "Up" as this means that the power down operation failed:
                if (dbVm.getStatus() == VMStatus.PoweringDown && vdsmVmDynamic.getStatus() == VMStatus.Up) {
                    AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), dbVm.getId());
                    auditLog(logable, AuditLogType.VM_POWER_DOWN_FAILED);
                }

                // log vm recovered from error
                if (dbVm.getStatus() == VMStatus.Paused && dbVm.getVmPauseStatus().isError()
                        && vdsmVmDynamic.getStatus() == VMStatus.Up) {
                    AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), dbVm.getId());
                    auditLog(logable, AuditLogType.VM_RECOVERED_FROM_PAUSE_ERROR);
                }

                if (isRunSucceeded(vdsmVmDynamic) || isMigrationSucceeded(vdsmVmDynamic)) {
                    // Vm moved to Up status - remove its record from Async
                    // reportedAndUnchangedVms handling
                    log.debug("removing VM '{}' from successful run VMs list", dbVm.getId());
                    succeededToRun = true;
                }
                afterMigrationFrom(vdsmVmDynamic, dbVm);

                if (dbVm.getStatus() != VMStatus.NotResponding
                        && vdsmVmDynamic.getStatus() == VMStatus.NotResponding) {
                    AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), dbVm.getId());
                    auditLog(logable, AuditLogType.VM_NOT_RESPONDING);
                }
                // check if vm is suspended and remove it from async list
                else if (vdsmVmDynamic.getStatus() == VMStatus.Paused) {
                    removeFromAsync = true;
                    if (dbVm.getStatus() != VMStatus.Paused) {
                        AuditLogableBase logable = new AuditLogableBase(getVdsManager().getVdsId(), dbVm.getId());
                        auditLog(logable, AuditLogType.VM_PAUSED);

                        // check exit message to determine why the VM is paused
                        if (vdsmVmDynamic.getPauseStatus().isError()) {
                            AuditLogType logType = vmPauseStatusToAuditLogType(vdsmVmDynamic.getPauseStatus());
                            logable = new AuditLogableBase(getVdsManager().getVdsId(), dbVm.getId());
                            auditLog(logable, logType);
                        }
                    }

                }
            }
            if (dbVm != null || vdsmVmDynamic.getStatus() != VMStatus.MigratingFrom) {
                if (updateVmRunTimeInfo()) {
                    saveDynamic(dbVm.getDynamicData());
                }
            }
            if (dbVm != null) {
                updateVmStatistics();
                stable = true;
                if (!getVdsManager().isInitialized()) {
                    vmsMonitoring.getResourceManager().removeVmFromDownVms(
                            getVdsManager().getVdsId(),
                            vdsmVmDynamic.getId());
                }
            }
        } else {
            if (vdsmVmDynamic.getStatus() == VMStatus.MigratingTo) {
                stable = true;
            }

            VmDynamic vmDynamic = getDbFacade().getVmDynamicDao().get(vdsmVmDynamic.getId());
            if (vmDynamic == null || vmDynamic.getStatus() != VMStatus.Unknown) {
                saveDynamic(null);
            }
        }
        // compare between vm in cache and vm from vdsm
        if (dbVm != null && !stable) {
            removeVmFromCache();
        }
    }

    private boolean isRunSucceeded(VmDynamic vdsmVmDynamic) {
        return !EnumSet.of(VMStatus.Up, VMStatus.MigratingFrom).contains(dbVm.getStatus())
                && vdsmVmDynamic.getStatus() == VMStatus.Up;
    }

    private boolean isMigrationSucceeded(VmDynamic vdsmVmDynamic) {
        return dbVm.getStatus() == VMStatus.MigratingTo && vdsmVmDynamic.getStatus().isRunning();
    }

    private boolean updateVmRunTimeInfo() {
        boolean returnValue = false;

        if (dbVm == null) {
            dbVm = getDbFacade().getVmDao().get(vdsmVm.getVmDynamic().getId());
            // if vm exists in db update info
            if (dbVm != null) {
                // TODO: This is done to keep consistency with VmDao.getById(Guid).
                // It should probably be removed, but some research is required.
                dbVm.setInterfaces(getDbFacade()
                        .getVmNetworkInterfaceDao()
                        .getAllForVm(dbVm.getId()));

                if ((isVmMigratingToThisVds() && vdsmVm.getVmDynamic().getStatus().isRunning())
                        || vdsmVm.getVmDynamic().getStatus() == VMStatus.Up) {
                    succeededToRun = true;
                }
            }
        }
        if (dbVm != null) {
            // check if dynamic data changed - update cache and DB
            List<String> props = ObjectIdentityChecker.getChangedFields(
                    dbVm.getDynamicData(), vdsmVm.getVmDynamic());
            // remove all fields that should not be checked:
            props.removeAll(UNCHANGEABLE_FIELDS_BY_VDSM);

            if (vdsmVm.getVmDynamic().getStatus() != VMStatus.Up) {
                props.remove(VmDynamic.APPLICATIONS_LIST_FIELD_NAME);
                vdsmVm.getVmDynamic().setAppList(dbVm.getAppList());
            }
            // if anything else changed
            if (!props.isEmpty()) {
                dbVm.updateRunTimeDynamicData(vdsmVm.getVmDynamic(),
                        getVdsManager().getVdsId(),
                        getVdsManager().getVdsName());
                returnValue = true;
            }
        }

        return returnValue;
    }

    private boolean isVmMigratingToThisVds() {
        return dbVm.getStatus() == VMStatus.MigratingFrom && getVdsManager().getVdsId().equals(dbVm.getMigratingToVds());
    }

    private AuditLogType vmPauseStatusToAuditLogType(VmPauseStatus pauseStatus) {
        switch (pauseStatus) {
            case NOERR:
            case NONE:
                // user requested pause, no log needed
                return AuditLogType.UNASSIGNED;
            case ENOSPC:
                return AuditLogType.VM_PAUSED_ENOSPC;
            case EIO:
                return AuditLogType.VM_PAUSED_EIO;
            case EPERM:
                return AuditLogType.VM_PAUSED_EPERM;
            default:
                return AuditLogType.VM_PAUSED_ERROR;
        }
    }

    private void logVmStatusTransition() {
        if (dbVm.getStatus() != vdsmVm.getVmDynamic().getStatus()) {
            log.info("VM '{}'({}) moved from '{}' --> '{}'",
                    dbVm.getId(),
                    dbVm.getName(),
                    dbVm.getStatus().name(),
                    vdsmVm.getVmDynamic().getStatus().name());

            if (dbVm.getStatus() == VMStatus.Unknown) {
                logVmStatusTransionFromUnknown();
            }
        }
    }

    // del from cache all vms that not in vdsm
    private void removeVmFromCache() {
        // marks the vm was powered down by user but not reported as Down afterwards by vdsm
        boolean poweredDown = false;
        proceedVmBeforeDeletion();
        boolean migrating = dbVm.getStatus() == VMStatus.MigratingFrom;
        if (migrating) {
            handOverVM(dbVm);
        } else if (dbVm.getStatus() == VMStatus.PoweringDown) {
            poweredDown = true;
            clearVm(VmExitStatus.Normal,
                    String.format("VM %s shutdown complete", dbVm.getName()),
                    VmExitReason.Success);
        } else {
            clearVm(VmExitStatus.Error,
                    String.format("Could not find VM %s on host, assuming it went down unexpectedly",
                            dbVm.getName()),
                    VmExitReason.GenericError);
        }

        log.info("VM '{}({}) is running in db and not running in VDS '{}'",
                dbVm.getId(), dbVm.getName(), getVdsManager().getVdsName());

        if (!migrating && !rerun
                && vmsMonitoring.getResourceManager().isVmInAsyncRunningList(dbVm.getId())) {
            rerun = true;
            log.info("add VM '{}' to rerun treatment", dbVm.getName());
        }
        // vm should be auto startup
        // not already in start up list
        // not in reported from vdsm at all (and was not powered-down before)
        // or reported from vdsm with error code
        else if (dbVm.isAutoStartup()
                && !autoVmToRun
                && (vdsmVm == null || vdsmVm.getVmDynamic().getExitStatus() != VmExitStatus.Normal)
                && !poweredDown) {
            autoVmToRun = true;
            log.info("add VM '{}' to HA rerun treatment", dbVm.getName());
        } else if (getVmManager() != null && getVmManager().isColdReboot()) {
            setColdRebootFlag();
        }
    }

    private void handOverVM(VM vmToRemove) {
        Guid destinationHostId = vmToRemove.getMigratingToVds();

        // when the destination VDS is NonResponsive put the VM to Uknown like the rest of its VMs, else MigratingTo
        VMStatus newVmStatus =
                (VDSStatus.NonResponsive == getDbFacade().getVdsDao().get(destinationHostId).getStatus())
                        ? VMStatus.Unknown
                        : VMStatus.MigratingTo;

        // handing over the VM to the DST by marking it running on it. it will now be its SRC host.
        vmToRemove.setRunOnVds(destinationHostId);

        log.info("Handing over VM '{}'({}) to Host '{}'. Setting VM to status '{}'",
                vmToRemove.getName(),
                vmToRemove.getId(),
                destinationHostId,
                newVmStatus);

        // if the DST host goes unresponsive it will take care all MigratingTo and unknown VMs
        vmsMonitoring.getResourceManager().internalSetVmStatus(vmToRemove, newVmStatus);

        // save the VM state
        saveDynamic(vmToRemove.getDynamicData());
        saveStatistics();
        saveVmInterfaces();
    }

    private boolean inMigrationTo(VmDynamic runningVm, VM vmToUpdate) {
        if (runningVm.getStatus() == VMStatus.MigratingTo) {
            // in migration
            log.info(
                    "RefreshVmList VM id '{}' is migrating to VDS '{}' ignoring it in the refresh until migration is done",
                    runningVm.getId(),
                    getVdsManager().getVdsName());
            return true;
        }

        if (vmToUpdate == null && runningVm.getStatus() != VMStatus.MigratingFrom) {
            // check if the vm exists on another vds
            VmDynamic vmDynamic = getDbFacade().getVmDynamicDao().get(runningVm.getId());
            if (vmDynamic != null && vmDynamic.getRunOnVds() != null
                    && !vmDynamic.getRunOnVds().equals(getVdsManager().getVdsId()) && runningVm.getStatus() != VMStatus.Up) {
                log.info(
                        "RefreshVmList VM id '{}' status = '{}' on VDS '{}' ignoring it in the refresh until migration is done",
                        runningVm.getId(),
                        runningVm.getStatus(),
                        getVdsManager().getVdsName());
                return true;
            }
        }

        return false;
    }

    private void afterMigrationFrom(VmDynamic runningVm, VM vmToUpdate) {
        VMStatus oldVmStatus = vmToUpdate.getStatus();
        VMStatus currentVmStatus = runningVm.getStatus();

        // if the VM's status on source host was MigratingFrom and now the VM is running and its status
        // is not MigratingFrom, it means the migration failed
        if (oldVmStatus == VMStatus.MigratingFrom && currentVmStatus != VMStatus.MigratingFrom
                && currentVmStatus.isRunning()) {
            rerun = true;
            log.info("Adding VM '{}' to re-run list", runningVm.getId());
            vmToUpdate.setMigratingToVds(null);
            vmToUpdate.setMigrationProgressPercent(0);
            saveStatistics();
        }
    }

    private void logVmStatusTransionFromUnknown() {
        final AuditLogableBase auditLogable = new AuditLogableBase();
        auditLogable.setVmId(dbVm.getId());
        auditLogable.addCustomValue("VmStatus", vdsmVm.getVmDynamic().getStatus().toString());
        auditLogDirector.log(auditLogable, AuditLogType.VM_STATUS_RESTORED);
    }

    private void updateVmStatistics() {
        // check if time for vm statistics refresh - update cache and DB
        if (vmsMonitoring.isTimeToUpdateVmStatistics()) {
            dbVm.updateRunTimeStatisticsData(vdsmVm.getVmStatistics(), dbVm);
            saveStatistics();
            saveVmInterfaces();
            updateInterfaceStatistics();
            updateVmNumaNodeRuntimeInfo();
            updateDiskImageDynamics();
        }
    }

    private void updateDiskImageDynamics() {
        vmDiskImageDynamicToSave =  vdsmVm.getVmDynamic().getDisks().stream()
                .map(diskImageDynamic -> new Pair<>(dbVm.getId(), diskImageDynamic))
                .collect(Collectors.toList());
    }

    private void updateInterfaceStatistics() {
        if (vdsmVm.getVmStatistics().getInterfaceStatistics() == null) {
            return;
        }

        if (dbVm.getInterfaces() == null || dbVm.getInterfaces().isEmpty()) {
            dbVm.setInterfaces(getDbFacade().getVmNetworkInterfaceDao().getAllForVm(dbVm.getId()));
        }
        List<String> macs = new ArrayList<>();

        dbVm.setUsageNetworkPercent(0);

        NetworkStatisticsBuilder statsBuilder = new NetworkStatisticsBuilder(dbVm.getClusterCompatibilityVersion());

        for (VmNetworkInterface ifStats : vdsmVm.getVmStatistics().getInterfaceStatistics()) {
            boolean firstTime = !macs.contains(ifStats.getMacAddress());

            VmNetworkInterface vmIface = null;
            for (VmNetworkInterface tempIf : dbVm.getInterfaces()) {
                if (tempIf.getMacAddress().equals(ifStats.getMacAddress())) {
                    vmIface = tempIf;
                    break;
                }
            }
            if (vmIface == null) {
                continue;
            }

            // RX rate and TX rate are reported by VDSM in % (minimum value
            // 0, maximum value 100)
            // Rx drop and TX drop are reported in packet numbers

            // if rtl+pv it will get here 2 times (we take the max one)
            if (firstTime) {
                statsBuilder.updateExistingInterfaceStatistics(vmIface, ifStats);
            } else {
                vmIface.getStatistics().setReceiveRate(Math.max(vmIface.getStatistics().getReceiveRate(),
                        ifStats.getStatistics().getReceiveRate()));
                vmIface.getStatistics().setReceiveDropRate(Math.max(vmIface.getStatistics().getReceiveDropRate(),
                        ifStats.getStatistics().getReceiveDropRate()));
                vmIface.getStatistics().setTransmitRate(Math.max(vmIface.getStatistics().getTransmitRate(),
                        ifStats.getStatistics().getTransmitRate()));
                vmIface.getStatistics().setTransmitDropRate(Math.max(vmIface.getStatistics().getTransmitDropRate(),
                        ifStats.getStatistics().getTransmitDropRate()));
            }
            vmIface.setVmId(dbVm.getId());

            if (ifStats.getSpeed() != null && vmIface.getStatistics().getReceiveRate() != null
                    && vmIface.getStatistics().getReceiveRate() > 0) {

                double rx_percent = vmIface.getStatistics().getReceiveRate();
                double tx_percent = vmIface.getStatistics().getTransmitRate();

                dbVm.setUsageNetworkPercent(Math.max(dbVm.getUsageNetworkPercent(),
                        (int) Math.max(rx_percent, tx_percent)));
            }

            if (firstTime) {
                macs.add(ifStats.getMacAddress());
            }
        }

        Integer maxPercent = 100;
        dbVm.setUsageNetworkPercent((dbVm.getUsageNetworkPercent() > maxPercent) ?
                maxPercent :
                dbVm.getUsageNetworkPercent());
        Integer usageHistoryLimit = Config.getValue(ConfigValues.UsageHistoryLimit);
        dbVm.addNetworkUsageHistory(dbVm.getUsageNetworkPercent(), usageHistoryLimit);
    }

    private void saveVmInterfaces() {
        saveVmInterfaces = true;
    }

    /**
     * Prepare the VM Guest Agent network devices for update. <br>
     * The evaluation of the network devices for update is done by comparing the calculated hash of the network devices
     * from VDSM to the latest hash kept on engine side.
     */
    private void prepareGuestAgentNetworkDevicesForUpdate() {
        VmDynamic vdsmVmDynamic = vdsmVm.getVmDynamic();
        if (vdsmVmDynamic != null) {
            if (dbVm != null) {
                List<VmGuestAgentInterface> vmGuestAgentInterfaces = vdsmVm.getVmGuestAgentInterfaces();
                int guestAgentNicHash = vmGuestAgentInterfaces == null ? 0 : vmGuestAgentInterfaces.hashCode();
                if (guestAgentNicHash != vdsmVmDynamic.getGuestAgentNicsHash()) {
                    if (vmDynamicToSave == null) {
                        saveDynamic(dbVm.getDynamicData());
                    }
                    updateGuestAgentInterfacesChanges(
                            vmDynamicToSave,
                            vmGuestAgentInterfaces,
                            guestAgentNicHash);
                }
            }
        }
    }

    protected void updateLunDisks() {
        // Looping only over powering up VMs as LUN device size
        // is updated by VDSM only once when running a VM.
        if (poweringUp) {
            Map<String, LUNs> lunsMap = vdsmVm.getLunsMap();
            if (lunsMap.isEmpty()) {
                // LUNs list from getVmStats hasn't been updated yet or VDSM doesn't support LUNs list retrieval.
                return;
            }

            vmLunDisksToSave = new ArrayList<>();
            List<Disk> vmDisks = getDbFacade().getDiskDao().getAllForVm(vdsmVm.getVmDynamic().getId(), true);
            for (Disk disk : vmDisks) {
                if (disk.getDiskStorageType() != DiskStorageType.LUN) {
                    continue;
                }

                LUNs lunFromDB = ((LunDisk) disk).getLun();
                LUNs lunFromMap = lunsMap.get(lunFromDB.getId());

                // LUN's device size might be returned as zero in case of an error in VDSM;
                // Hence, verify before updating.
                if (lunFromMap.getDeviceSize() != 0 && lunFromMap.getDeviceSize() != lunFromDB.getDeviceSize()) {
                    // Found a mismatch - set LUN for update
                    log.info("Updated LUN device size - ID: '{}', previous size: '{}', new size: '{}'.",
                            lunFromDB.getLUNId(), lunFromDB.getDeviceSize(), lunFromMap.getDeviceSize());

                    lunFromDB.setDeviceSize(lunFromMap.getDeviceSize());
                    vmLunDisksToSave.add(lunFromDB);
                }
            }
        }
    }

    protected void updateVmJobs() {
        if (vdsmVm.getVmStatistics().getVmJobs() == null) {
            // If no vmJobs key was returned, we can't presume anything about the jobs; save them all
            log.debug("No vmJob data returned from VDSM, preserving existing jobs");
            return;
        }

        vmJobsToUpdate = new HashMap<>();
        vmJobIdsToRemove = new ArrayList<>();

        // Only jobs that were in the DB before our update may be updated/removed;
        // others are completely ignored for the time being
        Map<Guid, VmJob> jobsFromDb = getDbFacade().getVmJobDao().getAllForVm(vdsmVm.getVmDynamic().getId()).stream()
                .filter(job -> vmsMonitoring.getExistingVmJobIds().contains(job.getId()))
                .collect(Collectors.toMap(VmJob::getId, Function.identity()));

        Set<Guid> vmJobIdsToIgnore = new HashSet<>();
        vdsmVm.getVmStatistics().getVmJobs().stream()
        .filter(job -> jobsFromDb.containsKey(job.getId()))
        .forEach(job -> {
            if (jobsFromDb.get(job.getId()).equals(job)) {
                // Same data, no update needed.  It would be nice if a caching
                // layer would take care of this for us.
                vmJobIdsToIgnore.add(job.getId());
                log.info("VM job '{}': In progress (no change)", job.getId());
            } else {
                vmJobsToUpdate.put(job.getId(), job);
                log.info("VM job '{}': In progress, updating", job.getId());
            }
        });

        // Any existing jobs not saved need to be removed
        jobsFromDb.keySet().stream()
        .filter(jobId -> !vmJobsToUpdate.containsKey(jobId) && !vmJobIdsToIgnore.contains(jobId))
        .forEach(jobId -> {
            vmJobIdsToRemove.add(jobId);
            log.info("VM job '{}': Deleting", jobId);
        });
    }

    private void updateVmNumaNodeRuntimeInfo() {
        VmStatistics statistics = vdsmVm.getVmStatistics();
        if (!dbVm.getStatus().isRunning()) {
            dbVm.getStatisticsData().getvNumaNodeStatisticsList().clear();
            return;
        }

        //Build numa nodes map of the host which the dbVm is running on with node index as the key
        Map<Integer, VdsNumaNode> runOnVdsAllNumaNodesMap = new HashMap<>();
        List<VdsNumaNode> runOnVdsAllNumaNodes =
                getDbFacade().getVdsNumaNodeDao().getAllVdsNumaNodeByVdsId(dbVm.getRunOnVds());
        for (VdsNumaNode vdsNumaNode : runOnVdsAllNumaNodes) {
            runOnVdsAllNumaNodesMap.put(vdsNumaNode.getIndex(), vdsNumaNode);
        }

        //Build numa nodes map of the dbVm with node index as the key
        Map<Integer, VmNumaNode> vmAllNumaNodesMap = new HashMap<>();
        List<VmNumaNode> vmAllNumaNodes = getDbFacade().getVmNumaNodeDao().getAllVmNumaNodeByVmId(dbVm.getId());
        for (VmNumaNode vmNumaNode : vmAllNumaNodes) {
            vmAllNumaNodesMap.put(vmNumaNode.getIndex(), vmNumaNode);
        }

        //Initialize the unpinned dbVm numa nodes list with the runtime pinning information
        List<VmNumaNode> vmNumaNodesNeedUpdate = new ArrayList<>();
        for (VmNumaNode vNode : statistics.getvNumaNodeStatisticsList()) {
            VmNumaNode dbVmNumaNode = vmAllNumaNodesMap.get(vNode.getIndex());
            if (dbVmNumaNode != null) {
                vNode.setId(dbVmNumaNode.getId());
                List<Integer> pinnedNodes = NumaUtils.getPinnedNodeIndexList(dbVmNumaNode.getVdsNumaNodeList());
                List<Pair<Guid, Pair<Boolean, Integer>>> runTimePinList = new ArrayList<>();
                for (Pair<Guid, Pair<Boolean, Integer>> pair : vNode.getVdsNumaNodeList()) {
                    if (!pinnedNodes.contains(pair.getSecond().getSecond()) &&
                            runOnVdsAllNumaNodesMap.containsKey(pair.getSecond().getSecond())) {
                        pair.setFirst(runOnVdsAllNumaNodesMap.get(pair.getSecond().getSecond()).getId());
                        pair.getSecond().setFirst(false);
                        runTimePinList.add(pair);
                    }
                }
                if (!runTimePinList.isEmpty()) {
                    vNode.setVdsNumaNodeList(runTimePinList);
                    vmNumaNodesNeedUpdate.add(vNode);
                }
            }
        }
        dbVm.getStatisticsData().getvNumaNodeStatisticsList().addAll(vmNumaNodesNeedUpdate);
    }

    /**** Helpers and sub-methods ****/

    private void updateGuestAgentInterfacesChanges(
            VmDynamic vmDynamic,
            List<VmGuestAgentInterface> vmGuestAgentInterfaces,
            int guestAgentNicHash) {
        vmDynamic.setGuestAgentNicsHash(guestAgentNicHash);
        vmDynamic.setVmIp(extractVmIpsFromGuestAgentInterfaces(vmGuestAgentInterfaces));
        vmGuestAgentNics = vmGuestAgentInterfaces;
    }

    private String extractVmIpsFromGuestAgentInterfaces(List<VmGuestAgentInterface> nics) {
        if (nics == null || nics.isEmpty()) {
            return null;
        }

        List<String> ips = new ArrayList<>();
        for (VmGuestAgentInterface nic : nics) {
            if (nic.getIpv4Addresses() != null) {
                ips.addAll(nic.getIpv4Addresses());
            }
        }
        return ips.isEmpty() ? null : StringUtils.join(ips, " ");
    }

    protected boolean isBalloonWorking(VmBalloonInfo balloonInfo) {
        return Math.abs(balloonInfo.getBalloonLastMemory() - balloonInfo.getBalloonTargetMemory())
                > Math.abs(balloonInfo.getCurrentMemory() - balloonInfo.getBalloonTargetMemory());
    }

    public DbFacade getDbFacade() {
        return vmsMonitoring.getDbFacade();
    }

    protected void auditLog(AuditLogableBase auditLogable, AuditLogType logType) {
        auditLogDirector.log(auditLogable, logType);
    }

    private void setColdRebootFlag() {
        coldRebootVmToRun = true;
        getVmManager().setColdReboot(false);
        log.info("add VM '{}' to cold reboot treatment", dbVm.getName());
    }

    public boolean isRerun() {
        return rerun;
    }

    public VM getDbVm() {
        return dbVm;
    }

    public boolean isSuccededToRun() {
        return succeededToRun;
    }

    public boolean isAutoVmToRun() {
        return autoVmToRun;
    }

    public boolean isClientIpChanged() {
        return clientIpChanged;
    }

    public VmInternalData getVdsmVm() {
        return vdsmVm;
    }

    public boolean isPoweringUp() {
        return poweringUp;
    }

    public boolean isMovedToDown() {
        return movedToDown;
    }

    public boolean isRemoveFromAsync() {
        return removeFromAsync;
    }

    public boolean isExternalVm() {
        return externalVm;
    }

    public VdsManager getVdsManager() {
        return vmsMonitoring.getVdsManager();
    }

    private VmManager getVmManager() {
        if (getDbVm() == null) {
            return null;
        }
        return vmsMonitoring.getVmManager(getDbVm().getId());
    }

    public boolean isHostedEngineUnmanaged() {
        return hostedEngineUnmanaged;
    }

    public boolean isColdRebootVmToRun() {
        return coldRebootVmToRun;
    }

    public Collection<VmJob> getVmJobsToUpdate() {
        return vmJobsToUpdate != null ? vmJobsToUpdate.values() : Collections.emptyList();
    }

    public List<Guid> getVmJobIdsToRemove() {
        return vmJobIdsToRemove != null ? vmJobIdsToRemove : Collections.emptyList();
    }

    public Collection<Pair<Guid, DiskImageDynamic>> getVmDiskImageDynamicToSave() {
        return vmDiskImageDynamicToSave != null ? vmDiskImageDynamicToSave : Collections.emptyList();
    }

    public List<LUNs> getVmLunDisksToSave() {
        return vmLunDisksToSave != null ? vmLunDisksToSave : Collections.emptyList();
    }

    public List<VmGuestAgentInterface> getVmGuestAgentNics() {
        return vmGuestAgentNics != null ? vmGuestAgentNics : Collections.emptyList();
    }

}
