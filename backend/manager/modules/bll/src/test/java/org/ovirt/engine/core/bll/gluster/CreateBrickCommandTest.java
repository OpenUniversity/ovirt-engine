package org.ovirt.engine.core.bll.gluster;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.ovirt.engine.core.utils.MockConfigRule.mockConfig;

import java.util.Arrays;
import java.util.Collections;

import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mock;
import org.ovirt.engine.core.bll.BaseCommandTest;
import org.ovirt.engine.core.bll.utils.GlusterUtil;
import org.ovirt.engine.core.common.action.gluster.CreateBrickParameters;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.RaidType;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.gluster.StorageDevice;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.compat.Version;
import org.ovirt.engine.core.utils.MockConfigRule;

public class CreateBrickCommandTest extends BaseCommandTest {

    private final Guid CLUSTER_ID = new Guid("b399944a-81ab-4ec5-8266-e19ba7c3c9d1");
    private final Guid HOST_ID = new Guid("b399944a-81ab-4ec5-8266-e19ba7c3c9d1");

    @Mock
    private VDS vds;

    @Mock
    private GlusterUtil glusterUtil;

    @Mock
    private Cluster cluster;

    /**
     * The command under test.
     */
    private CreateBrickCommand cmd;

    @ClassRule
    public static MockConfigRule mcr = new MockConfigRule(
            mockConfig(ConfigValues.GlusterBrickProvisioningEnabled, Version.v3_6, true),
            mockConfig(ConfigValues.GlusterBrickProvisioningEnabled, Version.v3_5, false)
            );

    @Test
    public void validateSucceeds() {
        cmd = spy(new CreateBrickCommand(new CreateBrickParameters(HOST_ID,
                "brick1",
                "/gluster-bricks/brick1",
                RaidType.RAID0,
                null,
                null, Arrays.asList(getStorageDevice("sda", null)))));
        prepareMocks(cmd, VDSStatus.Up);
        assertTrue(cmd.validate());
    }

    @Test
    public void validateFailsForCluster() {
        cmd = spy(new CreateBrickCommand(new CreateBrickParameters()));
        prepareMocks(cmd, VDSStatus.Up);
        mockIsGlusterEnabled(false);
        assertFalse(cmd.validate());

        mockIsGlusterEnabled(true);
        mockCompatibilityVersion(Version.v3_5);
        assertFalse(cmd.validate());
    }

    @Test
    public void validateFailsForVdsNonUp() {
        cmd = spy(new CreateBrickCommand(new CreateBrickParameters()));
        prepareMocks(cmd, VDSStatus.Down);
        assertFalse(cmd.validate());

        doReturn(VDSStatus.Error).when(vds).getStatus();
        assertFalse(cmd.validate());

        doReturn(VDSStatus.Maintenance).when(vds).getStatus();
        assertFalse(cmd.validate());
    }

    @Test
    public void validateFailsForNoStorageDevice() {
        cmd = spy(new CreateBrickCommand(new CreateBrickParameters(HOST_ID,
                "brick1",
                "/gluster-bricks/brick1",
                RaidType.RAID0,
                null,
                null, Collections.<StorageDevice> emptyList())));
        prepareMocks(cmd, VDSStatus.Up);
        assertFalse(cmd.validate());
    }

    @Test
    public void validateFailsForDeviceAlreadyInUse() {
        StorageDevice storageDevice = getStorageDevice("sda", null);
        storageDevice.setCanCreateBrick(false);
        cmd = spy(new CreateBrickCommand(new CreateBrickParameters(HOST_ID,
                "brick1",
                "/gluster-bricks/brick1",
                RaidType.RAID0,
                null,
                null, Arrays.asList(storageDevice))));
        prepareMocks(cmd, VDSStatus.Up);
        assertFalse(cmd.validate());
    }

    @Test
    public void validateFailsForDifferentStorageDevice() {
        StorageDevice storageDevice1 = getStorageDevice("sda", null);
        StorageDevice storageDevice2 = getStorageDevice("sdb", null);
        storageDevice2.setDevType("SDA");

        cmd = spy(new CreateBrickCommand(new CreateBrickParameters(HOST_ID,
                "brick1",
                "/gluster-bricks/brick1",
                RaidType.RAID0,
                null,
                null, Arrays.asList(storageDevice1, storageDevice2))));
        prepareMocks(cmd, VDSStatus.Up);
        assertFalse(cmd.validate());
    }

    protected <T extends CreateBrickCommand> void prepareMocks(T command, VDSStatus status) {
        when(command.getCluster()).thenReturn(cluster);
        doReturn(vds).when(command).getVds();
        doReturn(status).when(vds).getStatus();
        mockIsGlusterEnabled(true);
        doReturn(glusterUtil).when(command).getGlusterUtil();
        when(glusterUtil.isGlusterBrickProvisioningSupported(eq(Version.v3_6), any(Guid.class))).thenReturn(true);
        when(glusterUtil.isGlusterBrickProvisioningSupported(eq(Version.v3_5), any(Guid.class))).thenReturn(false);
        mockCompatibilityVersion(Version.v3_6);
    }

    private void mockIsGlusterEnabled(boolean glusterService) {
        when(cluster.supportsGlusterService()).thenReturn(glusterService);
    }

    private void mockCompatibilityVersion(Version version) {
        when(cluster.getCompatibilityVersion()).thenReturn(version);
    }

    private StorageDevice getStorageDevice(String name, Guid id) {
        StorageDevice storageDevice = new StorageDevice();
        storageDevice.setCanCreateBrick(true);
        storageDevice.setDescription("Test Device" + name);
        storageDevice.setDevPath("/dev/" + name);
        storageDevice.setDevType("SCSI");
        storageDevice.setName(name);
        storageDevice.setSize(10000L);
        if (id == null) {
            storageDevice.setId(Guid.newGuid());
        } else {
            storageDevice.setId(id);
        }
        return storageDevice;
    }
}
