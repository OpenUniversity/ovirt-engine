package org.ovirt.engine.core.bll.storage.disk.cinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.CommandBase;
import org.ovirt.engine.core.bll.InternalCommandAttribute;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.snapshots.SnapshotsManager;
import org.ovirt.engine.core.bll.tasks.CommandCoordinatorUtil;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.action.CloneCinderDisksParameters;
import org.ovirt.engine.core.common.action.CreateCinderSnapshotParameters;
import org.ovirt.engine.core.common.action.ImagesContainterParametersBase;
import org.ovirt.engine.core.common.action.VdcActionParametersBase;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.action.VdcReturnValueBase;
import org.ovirt.engine.core.common.businessentities.storage.CinderDisk;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.SnapshotDao;

@InternalCommandAttribute
public class TryBackToAllCinderSnapshotsCommand<T extends CloneCinderDisksParameters> extends CommandBase<T> {

    @Inject
    private SnapshotDao snapshotDao;

    private final SnapshotsManager snapshotsManager = new SnapshotsManager();

    public TryBackToAllCinderSnapshotsCommand(T parameters) {
        this(parameters, null);
    }

    public TryBackToAllCinderSnapshotsCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    @Override
    protected void executeCommand() {
        boolean isSucceeded = true;
        Map<Guid, Guid> diskImageMap = new HashMap<>();
        for (CinderDisk disk : getParameters().getCinderDisks()) {
            ImagesContainterParametersBase params = buildChildCommandParameters(disk);
            Future<VdcReturnValueBase> future = CommandCoordinatorUtil.executeAsyncCommand(
                    VdcActionType.TryBackToCinderSnapshot,
                    params,
                    cloneContextAndDetachFromParent());
            try {
                VdcReturnValueBase vdcReturnValueBase = future.get();
                if (!vdcReturnValueBase.getSucceeded()) {
                    log.error("Error cloning Cinder disk for preview. '{}': {}", disk.getDiskAlias());
                    getReturnValue().setFault(vdcReturnValueBase.getFault());
                    isSucceeded = false;
                    break;
                }
                CinderDisk cinderDisk = vdcReturnValueBase.getActionReturnValue();
                diskImageMap.put(disk.getId(), cinderDisk.getImageId());
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error cloning Cinder disk for preview. '{}': {}", disk.getDiskAlias(), e.getMessage());
                isSucceeded = false;
            }
        }
        getReturnValue().setActionReturnValue(diskImageMap);
        persistCommand(getParameters().getParentCommand(), true);
        setSucceeded(isSucceeded);
    }

    private CreateCinderSnapshotParameters buildChildCommandParameters(CinderDisk cinderDisk) {
        CreateCinderSnapshotParameters createParams = new CreateCinderSnapshotParameters(cinderDisk.getImageId());
        createParams.setContainerId(cinderDisk.getId());
        createParams.setStorageDomainId(cinderDisk.getStorageIds().get(0));
        createParams.setEntityInfo(getParameters().getEntityInfo());
        createParams.setDestinationImageId(cinderDisk.getImageId());
        createParams.setVmSnapshotId(getParameters().getVmSnapshotId());
        createParams.setParentCommand(getActionType());
        createParams.setParentParameters(getParameters());
        return createParams;
    }

    @Override
    public CommandCallback getCallback() {
        return new CloneCinderDisksCommandCallback<>();
    }

    @Override
    protected void endWithFailure() {
        endActionOnDisks(false);
        setSucceeded(true);
    }

    @Override
    protected void endSuccessfully() {
        endActionOnDisks(true);
        setSucceeded(true);
    }

    protected List<VdcReturnValueBase> endActionOnDisks(boolean succeeded) {
        List<VdcReturnValueBase> returnValues = new ArrayList<>();
        for (VdcActionParametersBase p : getParameters().getImagesParameters()) {
            p.setTaskGroupSuccess(succeeded);

            VdcReturnValueBase returnValue = getBackend().endAction(
                    p.getCommandType(),
                    p,
                    getContext().clone().withoutCompensationContext().withoutExecutionContext().withoutLock());
            returnValues.add(returnValue);
        }
        return returnValues;
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.emptyList();
    }


}
