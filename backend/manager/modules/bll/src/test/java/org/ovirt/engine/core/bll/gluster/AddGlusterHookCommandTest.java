package org.ovirt.engine.core.bll.gluster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.action.gluster.GlusterHookManageParameters;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.gluster.GlusterHookEntity;
import org.ovirt.engine.core.common.businessentities.gluster.GlusterHookStatus;
import org.ovirt.engine.core.common.businessentities.gluster.GlusterServerHook;
import org.ovirt.engine.core.common.errors.EngineError;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.errors.VDSError;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSParametersBase;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.common.vdscommands.gluster.GlusterHookVDSParameters;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.VdsDao;


@RunWith(MockitoJUnitRunner.class)
public class AddGlusterHookCommandTest extends GlusterHookCommandTest<AddGlusterHookCommand<GlusterHookManageParameters>> {
    /**
     * The command under test.
     */
    AddGlusterHookCommand cmd;

    @Mock
    private VdsDao vdsDao;

    private static final Guid SERVER_ID = Guid.newGuid();

    private void setUpMocksForAdd() {
        setUpMocksForAdd(true);
    }

    private void setUpMocksForAdd(boolean hookFound) {
        setUpMocksForAdd(hookFound, getHookEntityWithMissing());
    }

    private void setUpMocksForAdd(boolean hookFound, GlusterHookEntity hook) {
        setUpMocksForAdd(hookFound, hook, VDSStatus.Up);
    }

    private void setUpMocksForAdd(VDSStatus status) {
        setUpMocksForAdd(true, getHookEntityWithMissing(), status);
    }

    private GlusterHookEntity getHookEntityWithMissing() {
        GlusterHookEntity hook = getHookEntity();
        List<GlusterServerHook> serverHooks = new ArrayList<>();
        serverHooks.add(getGlusterServerHook(0, GlusterHookStatus.MISSING));
        serverHooks.add(getGlusterServerHook(1, GlusterHookStatus.MISSING));
        serverHooks.add(getGlusterServerHook(2, GlusterHookStatus.MISSING));
        hook.setServerHooks(serverHooks);
        return hook;
    }

    private void setUpMocksForAdd(boolean hookFound, GlusterHookEntity hook, VDSStatus status) {
        setupMocks(cmd, hookFound, hook);
        doReturn(vdsDao).when(cmd).getVdsDao();
        when(vdsDao.get(any(Guid.class))).thenReturn(getServer(SERVER_ID, "gfs1", CLUSTER_ID, status));
    }

    private void mockBackend(boolean succeeded, EngineError errorCode) {
        doReturn(backend).when(cmd).getBackend();

        VDSReturnValue vdsReturnValue = new VDSReturnValue();
        vdsReturnValue.setSucceeded(succeeded);
        if (!succeeded) {
            vdsReturnValue.setVdsError(new VDSError(errorCode, ""));
        }
        when(vdsBrokerFrontend.runVdsCommand(eq(VDSCommandType.AddGlusterHook), argThat(anyHookVDS()))).thenReturn(
                vdsReturnValue);
    }

    private ArgumentMatcher<VDSParametersBase> anyHookVDS() {
        return new ArgumentMatcher<VDSParametersBase>() {

            @Override
            public boolean matches(Object argument) {
                if (!(argument instanceof GlusterHookVDSParameters)) {
                    return false;
                }
                return true;
            }
        };
    }

    @Test
    public void executeCommand() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(HOOK_ID)));
        setUpMocksForAdd();
        mockBackend(true, null);
        cmd.executeCommand();
        verify(hooksDao, times(1)).updateGlusterHook(any(GlusterHookEntity.class));
        verify(hooksDao, times(3)).removeGlusterServerHook(any(Guid.class), any(Guid.class));
        assertEquals(cmd.getAuditLogTypeValue(), AuditLogType.GLUSTER_HOOK_ADDED);
    }


    @Test
    public void executeCommandWhenFailed() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(HOOK_ID)));
        setUpMocksForAdd();
        mockBackend(false, EngineError.GlusterHookAddFailed);
        cmd.executeCommand();
        verify(hooksDao, never()).updateGlusterHook(any(GlusterHookEntity.class));
        verify(hooksDao, never()).removeGlusterServerHook(any(Guid.class), any(Guid.class));
        assertEquals(cmd.getAuditLogTypeValue(), AuditLogType.GLUSTER_HOOK_ADD_FAILED);
    }

    @Test
    public void validateSucceeds() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(HOOK_ID)));
        setUpMocksForAdd();
        assertTrue(cmd.validate());
    }

    @Test
    public void validateFailsOnNullHookId() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(null)));
        setUpMocksForAdd();
        assertFalse(cmd.validate());
        assertTrue(cmd.getReturnValue().getValidationMessages().contains(EngineMessage.ACTION_TYPE_FAILED_GLUSTER_HOOK_ID_IS_REQUIRED.toString()));
    }

    @Test
    public void validateFailsOnNoHook() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(HOOK_ID)));
        setUpMocksForAdd(false);
        assertFalse(cmd.validate());
        assertTrue(cmd.getReturnValue().getValidationMessages().contains(EngineMessage.ACTION_TYPE_FAILED_GLUSTER_HOOK_DOES_NOT_EXIST.toString()));
    }

    @Test
    public void validateFailsOnNoConflictServers() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(HOOK_ID)));
        GlusterHookEntity hook = getHookEntity();
        setUpMocksForAdd(true, hook);
        assertFalse(cmd.validate());
        assertTrue(cmd.getReturnValue().getValidationMessages().contains(EngineMessage.ACTION_TYPE_FAILED_GLUSTER_HOOK_NO_CONFLICT_SERVERS.toString()));
    }

    @Test
    public void validateFailsOnServerNotUp() {
        cmd = spy(new AddGlusterHookCommand(new GlusterHookManageParameters(HOOK_ID)));
        setUpMocksForAdd(VDSStatus.Down);
        assertFalse(cmd.validate());
        assertTrue(cmd.getReturnValue().getValidationMessages().contains(EngineMessage.ACTION_TYPE_FAILED_SERVER_STATUS_NOT_UP.toString()));
    }

}
