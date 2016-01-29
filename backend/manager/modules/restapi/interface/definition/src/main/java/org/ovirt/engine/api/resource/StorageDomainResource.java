/*
* Copyright (c) 2010 Red Hat, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*           http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.ovirt.engine.api.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.ovirt.engine.api.model.Action;
import org.ovirt.engine.api.model.Actionable;
import org.ovirt.engine.api.model.StorageDomain;

@Produces({ApiMediaType.APPLICATION_XML, ApiMediaType.APPLICATION_JSON})
public interface StorageDomainResource {
    @GET
    StorageDomain get();

    @PUT
    @Consumes({ApiMediaType.APPLICATION_XML, ApiMediaType.APPLICATION_JSON})
    StorageDomain update(StorageDomain domain);

    @Path("{action: (isattached|refreshluns)}/{oid}")
    ActionResource getActionResource(@PathParam("action") String action, @PathParam("oid") String oid);

    @Path("permissions")
    AssignedPermissionsResource getPermissionsResource();

    @Path("vms")
    StorageDomainVmsResource getVmsResource();

    @Path("templates")
    StorageDomainTemplatesResource getTemplatesResource();

    @Path("files")
    FilesResource getFilesResource();

    @Path("disks")
    DisksResource getDisksResource();

    @Path("storageconnections")
    StorageDomainServerConnectionsResource getStorageConnectionsResource();

    @Path("images")
    ImagesResource getImagesResource();

    @Path("disksnapshots")
    DiskSnapshotsResource getDiskSnapshotsResource();

    @Path("diskprofiles")
    AssignedDiskProfilesResource getDiskProfilesResource();

    @POST
    @Consumes({ApiMediaType.APPLICATION_XML, ApiMediaType.APPLICATION_JSON})
    @Actionable
    @Path("isattached")
    Response isAttached(Action action);

    @DELETE
    @Consumes({ ApiMediaType.APPLICATION_XML, ApiMediaType.APPLICATION_JSON })
    Response remove();

    @POST
    @Consumes({ApiMediaType.APPLICATION_XML, ApiMediaType.APPLICATION_JSON})
    @Actionable
    @Path("refreshluns")
    Response refreshLuns(Action action);
}
