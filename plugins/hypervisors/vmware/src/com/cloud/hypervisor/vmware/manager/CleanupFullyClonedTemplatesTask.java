package com.cloud.hypervisor.vmware.manager;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.vm.UserVmCloneSettingVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.UserVmCloneSettingDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.engine.orchestration.VolumeOrchestrator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.log4j.Logger;

import javax.inject.Inject;
import java.util.List;

/**
 * This Task marks templates that are only used as fully cloned templates and have been deleted from CloudStack for removal from primary stores.
 */
class CleanupFullyClonedTemplatesTask implements Runnable {

    private static final Logger s_logger = Logger.getLogger(CleanupFullyClonedTemplatesTask.class);

    @Inject
    private PrimaryDataStoreDao primaryStorageDao;
    @Inject
    private VMTemplatePoolDao templateDataStoreDao;
    @Inject
    private TemplateJoinDao templateDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private UserVmCloneSettingDao cloneSettingDao;

    private Thread mine;

    @Override
    public void run() {
        s_logger.info("running job to mark fully cloned templates for gc in thread " + mine.getName());

        if (HypervisorGuru.VmwareFullClone.value()) { // only run if full cloning is being used (might need to be more fine grained)
            queryAllPools();
        }
    }

    private void queryAllPools() {
        List<StoragePoolVO> storagePools = primaryStorageDao.listAll();
        for (StoragePoolVO pool : storagePools) {
            long zoneId = pool.getDataCenterId();
            queryPoolForTemplates(pool, zoneId);
        }
    }

    private void queryPoolForTemplates(StoragePoolVO pool, long zoneId) {
        // we don't need those specific to other hypervisor types
        if (pool.getHypervisor() == null || Hypervisor.HypervisorType.VMware.equals(pool.getHypervisor())) {
            if(s_logger.isDebugEnabled()) {
                s_logger.debug(mine.getName() + " is marking fully cloned templates in pool " + pool.getName());
            }
            List<VMTemplateStoragePoolVO> templatePrimaryDataStoreVOS = templateDataStoreDao.listByPoolId(pool.getId());
            for (VMTemplateStoragePoolVO templateMapping : templatePrimaryDataStoreVOS) {
                checkTemplateForRemoval(zoneId, templateMapping);
            }
        } else {
            if(s_logger.isDebugEnabled()) {
                s_logger.debug(mine.getName() + " is ignoring pool " + pool.getName() + " id == " + pool.getId());
            }
        }
    }

    private void checkTemplateForRemoval(long zoneId, VMTemplateStoragePoolVO templateMapping) {
        if (!templateMapping.getMarkedForGC()) {
            if(s_logger.isDebugEnabled()) {
                s_logger.debug(mine.getName() + " is checking template with id " + templateMapping.getTemplateId() + " for deletion from pool with id " + templateMapping.getPoolId());
            }

            TemplateJoinVO templateJoinVO = templateDao.findByIdIncludingRemoved(templateMapping.getPoolId());
            //  check if these are deleted (not removed is null)
            if (templateJoinVO.getRemoved() != null) { // meaning it is removed!
                //  see if we can find vms using it with user_vm_clone_setting != full
                markForGcAsNeeded(templateMapping, zoneId);
            }
        }
    }

    private void markForGcAsNeeded(VMTemplateStoragePoolVO templateMapping, long zoneId) {
        boolean used = false;
        List<VMInstanceVO> vms = vmInstanceDao.listNonExpungedByZoneAndTemplate(zoneId, templateMapping.getTemplateId());
        for (VMInstanceVO vm : vms) {
            UserVmCloneSettingVO cloneSetting = cloneSettingDao.findByVmId(vm.getId());
            if (VolumeOrchestrator.UserVmCloneType.linked.equals(VolumeOrchestrator.UserVmCloneType.valueOf(cloneSetting.getCloneType()))) {
                used = true;
            }
        }
        if (!used) {
            if(s_logger.isDebugEnabled()) {
                s_logger.debug(mine.getName() + " is marking template with id " + templateMapping.getTemplateId() + " for gc in pool with id " + templateMapping.getPoolId());
            }
            // else
            //  mark it for removal from primary store
            templateMapping.setMarkedForGC(true);
        }
    }
}
