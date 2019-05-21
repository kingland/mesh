package com.gentics.mesh.core.schema.versioning;

import static com.gentics.mesh.assertj.MeshAssertions.assertThat;
import static com.gentics.mesh.core.rest.job.JobStatus.COMPLETED;
import static com.gentics.mesh.test.ClientHelper.call;
import static com.gentics.mesh.test.TestSize.FULL;

import org.junit.Test;

import com.gentics.mesh.FieldUtil;
import com.gentics.mesh.core.rest.branch.BranchCreateRequest;
import com.gentics.mesh.core.rest.node.NodeUpdateRequest;
import com.gentics.mesh.core.rest.schema.impl.SchemaResponse;
import com.gentics.mesh.core.rest.schema.impl.SchemaUpdateRequest;
import com.gentics.mesh.test.context.AbstractMeshTest;
import com.gentics.mesh.test.context.MeshTestSetting;

@MeshTestSetting(testSize = FULL, startServer = true)
public class SchemaNodeVersioningEndpointTest extends AbstractMeshTest {

	@Test
	public void testDisableVersioning() {
		disableVersionedFlag();

		String nodeUuid = contentUuid();

		// 1. Update
		NodeUpdateRequest nodeUpdateRequest = new NodeUpdateRequest();
		nodeUpdateRequest.setLanguage("en");
		nodeUpdateRequest.setVersion("draft");
		nodeUpdateRequest.getFields().put("slug", FieldUtil.createStringField("new name"));
		call(() -> client().updateNode(projectName(), nodeUuid, nodeUpdateRequest));
		assertVersions(nodeUuid, "en", "D(2.1)=>P(2.0)=>(1.0)=>I(0.1)");

		// 2. Update
		nodeUpdateRequest.getFields().put("slug", FieldUtil.createStringField("new name2"));
		call(() -> client().updateNode(projectName(), nodeUuid, nodeUpdateRequest));
		assertVersions(nodeUuid, "en", "D(2.2)=>P(2.0)=>(1.0)=>I(0.1)");

		// 3. Update
		nodeUpdateRequest.getFields().put("slug", FieldUtil.createStringField("new name3"));
		call(() -> client().updateNode(projectName(), nodeUuid, nodeUpdateRequest));
		assertVersions(nodeUuid, "en", "D(2.3)=>P(2.0)=>(1.0)=>I(0.1)");

		// 4. Publish node
		call(() -> client().publishNode(projectName(), nodeUuid));
		assertVersions(nodeUuid, "en", "PD(3.0)=>(1.0)=>I(0.1)");

		// 5. Take node offline
		call(() -> client().takeNodeOffline(projectName(), nodeUuid));
		assertVersions(nodeUuid, "en", "D(3.0)=>(1.0)=>I(0.1)");

		// 6. Publish again
		call(() -> client().publishNode(projectName(), nodeUuid));
		assertVersions(nodeUuid, "en", "PD(4.0)=>(1.0)=>I(0.1)");
		// Idempotency
		call(() -> client().publishNode(projectName(), nodeUuid));
		assertVersions(nodeUuid, "en", "PD(4.0)=>(1.0)=>I(0.1)");

		// Now create a branch. A new initial edge should be created
		waitForJob(() -> {
			BranchCreateRequest branchCreateRequest = new BranchCreateRequest();
			branchCreateRequest.setName("branch1");
			branchCreateRequest.setLatest(false);
			call(() -> client().createBranch(projectName(), branchCreateRequest));
		});
		assertVersions(nodeUuid, "en", "PDI(4.0)=>(1.0)=>I(0.1)");

		// Update the node again
		nodeUpdateRequest.getFields().put("slug", FieldUtil.createStringField("new name4"));
		call(() -> client().updateNode(projectName(), nodeUuid, nodeUpdateRequest));
		assertVersions(nodeUuid, "en", "D(4.1)=>PI(4.0)=>(1.0)=>I(0.1)");

		// Publish it again and ensure that version 4.0 is not removed
		call(() -> client().publishNode(projectName(), nodeUuid));
		assertVersions(nodeUuid, "en", "PD(5.0)=>I(4.0)=>(1.0)=>I(0.1)");

	}

	private void disableVersionedFlag() {
		grantAdminRole();
		String contentSchemaUuid = tx(() -> schemaContainer("content").getUuid());
		assertThat(call(() -> client().findSchemaByUuid(contentSchemaUuid))).isVersioned();
		waitForJobs(() -> {
			SchemaResponse schema = call(() -> client().findSchemaByUuid(contentSchemaUuid));
			SchemaUpdateRequest updateRequest = schema.toUpdateRequest();
			updateRequest.setVersioned(false);
			call(() -> client().updateSchema(contentSchemaUuid, updateRequest));
		}, COMPLETED, 1);
		assertThat(call(() -> client().findSchemaByUuid(contentSchemaUuid))).isNotVersioned();
	}

}
