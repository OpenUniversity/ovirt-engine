package org.ovirt.engine.core.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.common.businessentities.QuotaEnforcementTypeEnum;
import org.ovirt.engine.core.common.businessentities.storage.CinderDisk;
import org.ovirt.engine.core.common.businessentities.storage.DiskContentType;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.DiskStorageType;
import org.ovirt.engine.core.common.businessentities.storage.ImageStatus;
import org.ovirt.engine.core.common.businessentities.storage.StorageType;
import org.ovirt.engine.core.common.businessentities.storage.VolumeClassification;
import org.ovirt.engine.core.common.businessentities.storage.VolumeFormat;
import org.ovirt.engine.core.common.businessentities.storage.VolumeType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.DbFacadeUtils;
import org.ovirt.engine.core.utils.GuidUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * <code>DiskImageDaoImpl</code> provides an implementation of {@link DiskImageDao} that uses previously
 * developed code from {@link org.ovirt.engine.core.dal.dbbroker.DbFacade}.
 */
@Named
@Singleton
public class DiskImageDaoImpl extends BaseDao implements DiskImageDao {

    @Override
    public DiskImage get(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("image_guid", id);

        return getCallsHandler().executeRead("GetImageByImageGuid", DiskImageRowMapper.instance, parameterSource);
    }

    @Override
    public DiskImage getSnapshotById(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("image_guid", id);

        return getCallsHandler().executeRead("GetSnapshotByGuid", DiskImageRowMapper.instance, parameterSource);
    }

    @Override
    public List<DiskImage> getAllSnapshotsForParent(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("parent_guid", id);
        return getCallsHandler().executeReadList("GetSnapshotByParentGuid",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getAllSnapshotsForLeaf(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("image_guid", id);
        return getCallsHandler().executeReadList("GetSnapshotByLeafGuid",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getAllSnapshotsForStorageDomain(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("storage_domain_id", id);

        return getCallsHandler().executeReadList("GetSnapshotsByStorageDomainId",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getAllSnapshotsForVmSnapshot(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("vm_snapshot_id", id);

        return getCallsHandler().executeReadList("GetSnapshotsByVmSnapshotId",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public DiskImage getDiskSnapshotForVmSnapshot(Guid diskId, Guid vmSnapshotId) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("vm_snapshot_id", vmSnapshotId)
                .addValue("image_group_id", diskId);

        return getCallsHandler().executeRead("GetDiskSnapshotForVmSnapshot",
                DiskImageRowMapper.instance,
                parameterSource);
    }


    @Override
    public List<DiskImage> getAllSnapshotsForImageGroup(Guid id) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("image_group_id", id);

        return getCallsHandler().executeReadList("GetSnapshotsByImageGroupId",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getAttachedDiskSnapshotsToVm(Guid vmId, Boolean isPlugged) {
        return getCallsHandler().executeReadList("GetAttachedDiskSnapshotsToVm", DiskImageRowMapper.instance,
                getCustomMapSqlParameterSource().addValue("vm_guid", vmId).addValue("is_plugged", isPlugged));
    }

    @Override
    public List<DiskImage> getAll() {
        throw new NotImplementedException();
    }

    @Override
    public DiskImage getAncestor(Guid id) {
        return getAncestor(id, null, false);
    }

    @Override
    public DiskImage getAncestor(Guid id, Guid userID, boolean isFiltered) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("image_guid", id).addValue("user_id", userID).addValue("is_filtered", isFiltered);

        return getCallsHandler().executeRead("GetAncestralImageByImageGuid",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getImagesWithNoDisk(Guid vmId) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("vm_id", vmId);

        return getCallsHandler().executeReadList("GetImagesWhichHaveNoDisk",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getAllForStorageDomain(Guid storageDomainId) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("storage_domain_id", storageDomainId);

        return getCallsHandler().executeReadList("GetAllForStorageDomain",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    @Override
    public List<DiskImage> getAllForDiskProfile(Guid diskProfileId) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("disk_profile_id", diskProfileId);

        return getCallsHandler().executeReadList("GetAllForDiskProfile",
                DiskImageRowMapper.instance,
                parameterSource);
    }

    protected static class DiskImageRowMapper extends AbstractDiskRowMapper<DiskImage> {

        public static final DiskImageRowMapper instance = new DiskImageRowMapper();

        private DiskImageRowMapper() {
        }

        @Override
        public DiskImage mapRow(ResultSet rs, int rowNum) throws SQLException {
            DiskImage entity = null;
            DiskStorageType diskStorageType = DiskStorageType.forValue(rs.getInt("disk_storage_type"));

            switch (diskStorageType) {
                case IMAGE:
                    entity = super.mapRow(rs, rowNum);
                    mapEntity(rs, entity);
                    break;
                case CINDER:
                    entity = CinderDiskRowMapper.instance.mapRow(rs, rowNum);
                    break;
            }

            return entity;
        }

        protected void mapEntity(ResultSet rs, DiskImage entity) throws SQLException {
            entity.setCreationDate(DbFacadeUtils.fromDate(rs
                    .getTimestamp("creation_date")));
            entity.setActualSizeInBytes(rs.getLong("actual_size"));
            entity.setDescription(rs.getString("description"));
            entity.setImageId(getGuidDefaultEmpty(rs, "image_guid"));
            entity.setImageTemplateId(getGuidDefaultEmpty(rs, "it_guid"));
            entity.setSize(rs.getLong("size"));
            entity.setParentId(getGuidDefaultEmpty(rs, "ParentId"));
            entity.setImageStatus(ImageStatus.forValue(rs
                    .getInt("imageStatus")));
            entity.setLastModified(DbFacadeUtils.fromDate(rs
                    .getTimestamp("lastModified")));
            entity.setAppList(rs.getString("app_list"));
            entity.setStorageIds(GuidUtils.getGuidListFromString(rs.getString("storage_id")));
            entity.setStorageTypes(getStorageTypesList(rs.getString("storage_type")));
            entity.setStoragesNames(split(rs.getString("storage_name")));
            entity.setVmSnapshotId(getGuid(rs, "vm_snapshot_id"));
            entity.setVolumeType(VolumeType.forValue(rs
                    .getInt("volume_type")));
            entity.setvolumeFormat(VolumeFormat.forValue(rs
                    .getInt("volume_format")));
            entity.setId(getGuidDefaultEmpty(rs, "image_group_id"));
            entity.setStoragePoolId(getGuid(rs, "storage_pool_id"));
            entity.setBoot(rs.getBoolean("boot"));
            entity.setReadRate(rs.getInt("read_rate"));
            entity.setWriteRate(rs.getInt("write_rate"));
            entity.setContentType(rs.getBoolean("ovf_store") ? DiskContentType.OVF_STORE : DiskContentType.DATA);
            entity.setReadLatency(rs.getObject("read_latency_seconds") != null ? rs.getDouble("read_latency_seconds")
                    : null);
            entity.setWriteLatency(rs.getObject("write_latency_seconds") != null ? rs.getDouble("write_latency_seconds")
                    : null);
            entity.setFlushLatency(rs.getObject("flush_latency_seconds") != null ? rs.getDouble("flush_latency_seconds")
                    : null);
            entity.setActive(Boolean.TRUE.equals(rs.getObject("active")));
            entity.setQuotaIds(getGuidListFromStringPreserveAllTokens(rs.getString("quota_id")));
            entity.setQuotaNames(splitPreserveAllTokens(rs.getString("quota_name")));
            entity.setQuotaEnforcementType(QuotaEnforcementTypeEnum.forValue(rs.getInt("quota_enforcement_type")));
            entity.setDiskProfileIds(getGuidListFromStringPreserveAllTokens(rs.getString("disk_profile_id")));
            entity.setDiskProfileNames(splitPreserveAllTokens(rs.getString("disk_profile_name")));
            entity.setVolumeClassification(VolumeClassification.forValue(rs.getInt("volume_classification")));
        }

        @Override
        protected DiskImage createDiskEntity() {
            return new DiskImage();
        }

        private ArrayList<StorageType> getStorageTypesList(String storageTypesString) throws SQLException {
            List<String> splitTypes = split(storageTypesString);
            if (splitTypes == null) {
                return null;
            }

            ArrayList<StorageType> types = new ArrayList<>();
            for (String typeStr : splitTypes) {
                try {
                    types.add(StorageType.forValue(Integer.parseInt(typeStr)));
                }
                catch (NumberFormatException e) {
                    throw new SQLException("Could not parse disk image storage domain type " + typeStr, e);
                }
            }
            return types;
        }

        /**
         * since quota can be null, we need to preserve null in the list
         */
        private ArrayList<String> splitPreserveAllTokens(String str) {
            if (StringUtils.isEmpty(str)) {
                return null;
            }

            return new ArrayList<>(Arrays.asList(StringUtils.splitPreserveAllTokens(str, SEPARATOR)));
        }

        /**
         * since some disk images can contain empty quota, we need to preserve null in the list.
         */
        private ArrayList<Guid> getGuidListFromStringPreserveAllTokens(String str) {
            ArrayList<Guid> guidList = new ArrayList<>();
            if (StringUtils.isEmpty(str)) {
                return new ArrayList<>();
            }
            for (String guidString : splitPreserveAllTokens(str)) {
                Guid guidToAdd = null;
                if (!StringUtils.isEmpty(guidString)) {
                    guidToAdd = Guid.createGuidFromString(guidString);
                }
                guidList.add(guidToAdd);
            }
            return guidList;
        }
    }

    protected static class CinderDiskRowMapper extends AbstractDiskRowMapper<CinderDisk> {

        public static final CinderDiskRowMapper instance = new CinderDiskRowMapper();

        private CinderDiskRowMapper() {
        }

        @Override
        public CinderDisk mapRow(ResultSet rs, int rowNum) throws SQLException {
            CinderDisk cinderDisk = super.mapRow(rs, rowNum);
            DiskImageRowMapper.instance.mapEntity(rs, cinderDisk);
            mapEntity(rs, cinderDisk);
            return cinderDisk;
        }

        private void mapEntity(ResultSet rs, CinderDisk entity) throws SQLException {
            entity.setCinderVolumeType(rs.getString("cinder_volume_type"));
        }

        @Override
        protected CinderDisk createDiskEntity() {
            return new CinderDisk();
        }
    }
}
