package com.gentics.mesh.search;

import static com.gentics.mesh.demo.TestDataProvider.PROJECT_NAME;
import static com.gentics.mesh.util.MeshAssert.assertSuccess;
import static com.gentics.mesh.util.MeshAssert.failingLatch;
import static com.gentics.mesh.util.MeshAssert.latchFor;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.codehaus.jettison.json.JSONException;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gentics.mesh.core.AbstractSpringVerticle;
import com.gentics.mesh.core.data.Language;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.User;
import com.gentics.mesh.core.data.node.Micronode;
import com.gentics.mesh.core.data.node.Node;
import com.gentics.mesh.core.data.node.field.list.MicronodeGraphFieldList;
import com.gentics.mesh.core.data.node.field.nesting.MicronodeGraphField;
import com.gentics.mesh.core.data.relationship.GraphPermission;
import com.gentics.mesh.core.data.root.MeshRoot;
import com.gentics.mesh.core.data.schema.SchemaContainerVersion;
import com.gentics.mesh.core.data.service.ServerSchemaStorage;
import com.gentics.mesh.core.rest.common.GenericMessageResponse;
import com.gentics.mesh.core.rest.micronode.MicronodeResponse;
import com.gentics.mesh.core.rest.node.NodeCreateRequest;
import com.gentics.mesh.core.rest.node.NodeListResponse;
import com.gentics.mesh.core.rest.node.NodeResponse;
import com.gentics.mesh.core.rest.node.NodeUpdateRequest;
import com.gentics.mesh.core.rest.node.field.MicronodeField;
import com.gentics.mesh.core.rest.project.ProjectCreateRequest;
import com.gentics.mesh.core.rest.project.ProjectResponse;
import com.gentics.mesh.core.rest.release.ReleaseCreateRequest;
import com.gentics.mesh.core.rest.schema.ListFieldSchema;
import com.gentics.mesh.core.rest.schema.Schema;
import com.gentics.mesh.core.rest.schema.SchemaReference;
import com.gentics.mesh.core.rest.schema.impl.ListFieldSchemaImpl;
import com.gentics.mesh.core.rest.schema.impl.MicronodeFieldSchemaImpl;
import com.gentics.mesh.core.rest.schema.impl.NumberFieldSchemaImpl;
import com.gentics.mesh.core.rest.tag.TagResponse;
import com.gentics.mesh.core.verticle.admin.AdminVerticle;
import com.gentics.mesh.core.verticle.eventbus.EventbusVerticle;
import com.gentics.mesh.core.verticle.node.NodeMigrationVerticle;
import com.gentics.mesh.core.verticle.node.NodeVerticle;
import com.gentics.mesh.core.verticle.project.ProjectVerticle;
import com.gentics.mesh.core.verticle.release.ReleaseVerticle;
import com.gentics.mesh.core.verticle.schema.SchemaVerticle;
import com.gentics.mesh.core.verticle.tagfamily.TagFamilyVerticle;
import com.gentics.mesh.query.impl.NodeRequestParameter;
import com.gentics.mesh.query.impl.NodeRequestParameter.LinkType;
import com.gentics.mesh.query.impl.PagingParameter;
import com.gentics.mesh.search.index.NodeIndexHandler;
import com.gentics.mesh.test.TestUtils;
import com.gentics.mesh.util.FieldUtil;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;

public class NodeSearchVerticleTest extends AbstractSearchVerticleTest implements BasicSearchCrudTestcases {

	@Autowired
	private NodeVerticle nodeVerticle;

	@Autowired
	private NodeIndexHandler nodeIndexHandler;

	@Autowired
	private SchemaVerticle schemaVerticle;

	@Autowired
	private NodeMigrationVerticle nodeMigrationVerticle;

	@Autowired
	private AdminVerticle adminVerticle;

	@Autowired
	private EventbusVerticle eventbusVerticle;

	@Autowired
	private TagFamilyVerticle tagFamilyVerticle;

	@Autowired
	private ProjectVerticle projectVerticle;

	@Autowired
	private ReleaseVerticle releaseVerticle;

	@Override
	@Before
	public void setupVerticleTest() throws Exception {
		super.setupVerticleTest();
		DeploymentOptions options = new DeploymentOptions();
		options.setWorker(true);
		vertx.deployVerticle(nodeMigrationVerticle, options);
	}

	@Override
	public List<AbstractSpringVerticle> getAdditionalVertices() {
		List<AbstractSpringVerticle> list = new ArrayList<>();
		list.add(searchVerticle);
		list.add(projectSearchVerticle);
		list.add(schemaVerticle);
		list.add(adminVerticle);
		list.add(nodeVerticle);
		list.add(eventbusVerticle);
		list.add(tagFamilyVerticle);
		list.add(projectVerticle);
		list.add(releaseVerticle);
		return list;
	}

	@Test
	public void testSearchAndSort() throws InterruptedException {
		fullIndex();

		String json = "{";
		json += "				\"sort\" : {";
		json += "			      \"created\" : {\"order\" : \"asc\"}";
		json += "			    },";
		json += "			    \"query\":{";
		json += "			        \"bool\" : {";
		json += "			            \"must\" : {";
		json += "			                \"term\" : { \"schema.name\" : \"content\" }";
		json += "			            }";
		json += "			        }";
		json += "			    }";
		json += "			}";

		String search = json;
		NodeListResponse response = call(
				() -> getClient().searchNodes(PROJECT_NAME, search, new NodeRequestParameter().draft()));
		assertNotNull(response);
		assertFalse(response.getData().isEmpty());

		long lastCreated = 0;
		for (NodeResponse nodeResponse : response.getData()) {
			if (lastCreated > nodeResponse.getCreated()) {
				fail("Found entry that was not sorted by create timestamp. Last entry: {" + lastCreated + "} current entry: {"
						+ nodeResponse.getCreated() + "}");
			} else {
				lastCreated = nodeResponse.getCreated();
			}
			assertEquals("content", nodeResponse.getSchema().getName());
		}
	}

	@Test
	@Override
	public void testDocumentDeletion() throws InterruptedException, JSONException {
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Concorde"),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals(1, response.getData().size());
		deleteNode(PROJECT_NAME, content("concorde").getUuid());

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Concorde"),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals("We added the delete action and therefore the document should no longer be part of the index.", 0, response.getData().size());

	}

	@Test
	public void testBogusQuery() {
		call(() -> getClient().searchNodes(PROJECT_NAME, "bogus}J}son"), BAD_REQUEST, "search_query_not_parsable");
	}

	@Test
	public void testCustomQuery() throws InterruptedException, JSONException {

		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME,
				getSimpleTermQuery("schema.name", "content"), new NodeRequestParameter().draft()));
		assertNotNull(response);
		assertFalse(response.getData().isEmpty());

	}

	@Test
	public void testSearchForChildNodes() throws JSONException, InterruptedException {
		fullIndex();

		Node parentNode = folder("news");

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME,
				getSimpleTermQuery("parentNode.uuid", parentNode.getUuid()), new NodeRequestParameter().draft()));
		assertNotNull(response);
		assertFalse(response.getData().isEmpty());
		// TODO verify the found nodes are correct
		// for (NodeResponse childNode : response.getData()) {
		// System.out.println(childNode.getUuid());
		// System.out.println(((StringField)childNode.getField("name")).getString());
		// }
	}

	@Test
	@Override
	public void testDocumentCreation() throws Exception {
		fullIndex();

		// Invoke a dummy search on an empty index
		String json = "{";
		json += "				\"sort\" : {";
		json += "			      \"created\" : {\"order\" : \"asc\"}";
		json += "			    },";
		json += "			    \"query\":{";
		json += "			        \"bool\" : {";
		json += "			            \"must\" : {";
		json += "			                \"term\" : { \"fields.name\" : \"bla\" }";
		json += "			            }";
		json += "			        }";
		json += "			    }";
		json += "			}";

		String search = json;
		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, search,
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals(0, response.getData().size());

		// create a new folder named "bla"
		NodeCreateRequest create = new NodeCreateRequest();
		create.setSchema(new SchemaReference().setName("folder").setUuid(schemaContainer("folder").getUuid()));
		create.setLanguage("en");
		create.getFields().put("name", FieldUtil.createStringField("bla"));
		create.setParentNodeUuid(folder("2015").getUuid());

		call(() -> getClient().createNode(PROJECT_NAME, create));

		// Search again and make sure we found our document
		response = call(() -> getClient().searchNodes(PROJECT_NAME, search,
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals("Check search result after document creation", 1, response.getData().size());
	}

	@Test
	@Override
	public void testDocumentUpdate() throws Exception {
		fullIndex();

		String newString = "ABCDEFGHI";
		Node node = content("concorde");

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("supersonic"),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals("Check hits for 'supersonic' before update", 1, response.getData().size());

		NodeUpdateRequest update = new NodeUpdateRequest();
		update.setLanguage("en");
		update.getFields().put("content", FieldUtil.createHtmlField(newString));
		call(() -> getClient().updateNode(PROJECT_NAME, node.getUuid(), update));

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("supersonic"),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals("Check hits for 'supersonic' after update", 0, response.getData().size());

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(newString),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals("Check hits for '" + newString + "' after update", 1, response.getData().size());
	}

	@Test
	public void testSearchContent() throws InterruptedException, JSONException {
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("the"),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));
		assertEquals(1, response.getData().size());
		assertEquals(1, response.getMetainfo().getTotalCount());
		for (NodeResponse nodeResponse : response.getData()) {
			assertNotNull(nodeResponse);
			assertNotNull(nodeResponse.getUuid());
		}

	}

	@Test
	public void testSearchContentResolveLinksAndLangFallback() throws InterruptedException, JSONException {
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("the"), new PagingParameter().setPage(1).setPerPage(2),
				new NodeRequestParameter().setResolveLinks(LinkType.FULL).setLanguages("de", "en").draft()));
		assertEquals(1, response.getData().size());
		assertEquals(1, response.getMetainfo().getTotalCount());
		for (NodeResponse nodeResponse : response.getData()) {
			assertNotNull(nodeResponse);
			assertNotNull(nodeResponse.getUuid());
		}
	}

	@Test
	public void testSearchContentResolveLinks() throws InterruptedException, JSONException {
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("the"), new PagingParameter().setPage(1).setPerPage(2),
				new NodeRequestParameter().setResolveLinks(LinkType.FULL).draft()));
		assertEquals(1, response.getData().size());
		assertEquals(1, response.getMetainfo().getTotalCount());
		for (NodeResponse nodeResponse : response.getData()) {
			assertNotNull(nodeResponse);
			assertNotNull(nodeResponse.getUuid());
		}

	}

	/**
	 * Search in only english language versions of nodes
	 *
	 * @throws InterruptedException
	 * @throws JSONException
	 */
	@Test
	public void testSearchEnglish() throws InterruptedException, JSONException {
		searchWithLanguages("en");
	}

	/**
	 * Searhc in only german language versions of nodes
	 *
	 * @throws InterruptedException
	 * @throws JSONException
	 */
	@Test
	public void testSearchGerman() throws InterruptedException, JSONException {
		searchWithLanguages("de");
	}

	/**
	 * Search for string which can be found in two language variants of a single node. We would expect two nodes in the result which have different language
	 * properties.
	 *
	 * @throws InterruptedException
	 * @throws JSONException
	 */
	@Test
	public void testSearchMultipleLanguages() throws InterruptedException, JSONException {
		searchWithLanguages("de", "en");
	}

	/**
	 * Do the search with the given set of expected languages and assert correctness of the result
	 *
	 * @param expectedLanguages
	 * @throws InterruptedException
	 * @throws JSONException
	 */
	protected void searchWithLanguages(String... expectedLanguages) throws InterruptedException, JSONException {
		fullIndex();

		Node node = content("concorde");

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("concorde"), new PagingParameter().setPage(1).setPerPage(100),
				new NodeRequestParameter().setLanguages(expectedLanguages).draft()));
		assertEquals("Check # of returned nodes", expectedLanguages.length, response.getData().size());
		assertEquals("Check total count", expectedLanguages.length, response.getMetainfo().getTotalCount());

		Set<String> foundLanguages = new HashSet<>();
		for (NodeResponse nodeResponse : response.getData()) {
			assertEquals("Check uuid of found node", node.getUuid(), nodeResponse.getUuid());
			foundLanguages.add(nodeResponse.getLanguage());
		}

		Set<String> notFound = new HashSet<>(Arrays.asList(expectedLanguages));
		notFound.removeAll(foundLanguages);
		assertTrue("Did not find nodes in expected languages: " + notFound, notFound.isEmpty());

		Set<String> unexpected = new HashSet<>(foundLanguages);
		unexpected.removeAll(Arrays.asList(expectedLanguages));
		assertTrue("Found nodes in unexpected languages: " + unexpected, unexpected.isEmpty());
	}

	@Test
	public void testSearchNumberRange() throws Exception {
		int numberValue = 1200;
		addNumberSpeedField(numberValue);
		fullIndex();

		// from 1 to 9
		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getRangeQuery("speed", 100, 9000),
				new NodeRequestParameter().draft()));
		assertEquals(1, response.getData().size());
	}

	@Test
	public void testSearchNumberRange2() throws Exception {
		int numberValue = 1200;
		addNumberSpeedField(numberValue);
		fullIndex();

		// from 9 to 1
		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getRangeQuery("speed", 900, 1500),
				new NodeRequestParameter().draft()));
		assertEquals("We could expect to find the node with the given seed number field since the value {" + numberValue
				+ "} is between the search range.", 1, response.getData().size());
	}

	@Test
	public void testSearchNumberRange3() throws Exception {
		int numberValue = 1200;
		addNumberSpeedField(numberValue);
		fullIndex();

		// out of bounds
		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getRangeQuery("speed", 1000, 90),
				new NodeRequestParameter().draft()));
		assertEquals("No node should be found since the range is invalid.", 0, response.getData().size());
	}

	@Test
	public void testSearchMicronode() throws Exception {
		addMicronodeField();
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Mickey"),
				new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));

		assertEquals("Check returned search results", 1, response.getData().size());
		assertEquals("Check total search results", 1, response.getMetainfo().getTotalCount());
		for (NodeResponse nodeResponse : response.getData()) {
			assertNotNull("Returned node must not be null", nodeResponse);
			assertEquals("Check result uuid", content("concorde").getUuid(), nodeResponse.getUuid());
		}
	}

	@Test
	public void testSearchMicronodeResolveLinks() throws Exception {
		addMicronodeField();
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Mickey"), new PagingParameter().setPage(1).setPerPage(2),
				new NodeRequestParameter().setResolveLinks(LinkType.FULL).draft()));

		assertEquals("Check returned search results", 1, response.getData().size());
		assertEquals("Check total search results", 1, response.getMetainfo().getTotalCount());
		for (NodeResponse nodeResponse : response.getData()) {
			assertNotNull("Returned node must not be null", nodeResponse);
			assertEquals("Check result uuid", content("concorde").getUuid(), nodeResponse.getUuid());
		}
	}

	@Test
	public void testSearchListOfMicronodes() throws Exception {
		addMicronodeListField();
		fullIndex();

		for (String firstName : Arrays.asList("Mickey", "Donald")) {
			for (String lastName : Arrays.asList("Mouse", "Duck")) {
				// valid names always begin with the same character
				boolean expectResult = firstName.substring(0, 1).equals(lastName.substring(0, 1));

				NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getNestedVCardListSearch(firstName, lastName),
						new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().draft()));

				if (expectResult) {
					assertEquals("Check returned search results", 1, response.getData().size());
					assertEquals("Check total search results", 1, response.getMetainfo().getTotalCount());
					for (NodeResponse nodeResponse : response.getData()) {
						assertNotNull("Returned node must not be null", nodeResponse);
						assertEquals("Check result uuid", content("concorde").getUuid(), nodeResponse.getUuid());
					}
				} else {
					assertEquals("Check returned search results", 0, response.getData().size());
					assertEquals("Check total search results", 0, response.getMetainfo().getTotalCount());
				}
			}
		}
	}

	@Test
	public void testSearchListOfMicronodesResolveLinks() throws Exception {
		addMicronodeListField();
		fullIndex();

		for (String firstName : Arrays.asList("Mickey", "Donald")) {
			for (String lastName : Arrays.asList("Mouse", "Duck")) {
				// valid names always begin with the same character
				boolean expectResult = firstName.substring(0, 1).equals(lastName.substring(0, 1));

				NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getNestedVCardListSearch(firstName, lastName),
						new PagingParameter().setPage(1).setPerPage(2), new NodeRequestParameter().setResolveLinks(LinkType.FULL).draft()));

				if (expectResult) {
					assertEquals("Check returned search results", 1, response.getData().size());
					assertEquals("Check total search results", 1, response.getMetainfo().getTotalCount());
					for (NodeResponse nodeResponse : response.getData()) {
						assertNotNull("Returned node must not be null", nodeResponse);
						assertEquals("Check result uuid", content("concorde").getUuid(), nodeResponse.getUuid());
					}
				} else {
					assertEquals("Check returned search results", 0, response.getData().size());
					assertEquals("Check total search results", 0, response.getMetainfo().getTotalCount());
				}
			}
		}
	}

	@Test
	public void testSchemaMigrationNodeSearchTest() throws Exception {
		Node concorde = content("concorde");
		fullIndex();

		CountDownLatch latch = TestUtils.latchForMigrationCompleted(getClient());

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleTermQuery("uuid", concorde.getUuid()),
				new PagingParameter().setPage(1).setPerPage(10), new NodeRequestParameter().setLanguages("en", "de").draft()));
		assertEquals("We expect to find the two language versions.", 2, response.getData().size());

		SchemaContainerVersion schemaVersion = concorde.getSchemaContainer().getLatestVersion();

		Schema schema = schemaVersion.getSchema();
		schema.addField(FieldUtil.createStringFieldSchema("extraField"));

		// Clear the schema storage in order to purge the reference from the storage which we would otherwise modify.
		ServerSchemaStorage.getInstance().clear();

		Future<GenericMessageResponse> migrationFuture = getClient().updateSchema(concorde.getSchemaContainer().getUuid(), schema);
		latchFor(migrationFuture);
		assertSuccess(migrationFuture);
		expectResponseMessage(migrationFuture, "migration_invoked", "content");

		// Wait for migration to complete
		failingLatch(latch);

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleTermQuery("uuid", concorde.getUuid()), new PagingParameter().setPage(1).setPerPage(10),
				new NodeRequestParameter().setLanguages("en", "de").draft()));

		assertEquals("We only expect to find the two language versions.", 2, response.getData().size());
	}

	@Test
	public void testSearchManyNodesWithMicronodes() throws Exception {
		int numAdditionalNodes = 99;
		addMicronodeField();
		User user = user();
		Language english = english();
		Node concorde = content("concorde");

		Project project = concorde.getProject();
		Node parentNode = concorde.getParentNode();
		SchemaContainerVersion schemaVersion = concorde.getSchemaContainer().getLatestVersion();

		for (int i = 0; i < numAdditionalNodes; i++) {
			Node node = parentNode.create(user, schemaVersion, project);
			MicronodeGraphField vcardField = node
					.createGraphFieldContainer(english, node.getProject().getLatestRelease(), user)
					.createMicronode("vcard", microschemaContainers().get("vcard").getLatestVersion());
			vcardField.getMicronode().createString("firstName").setString("Mickey");
			vcardField.getMicronode().createString("lastName").setString("Mouse");
			role().grantPermissions(node, GraphPermission.READ_PERM);
		}
		MeshRoot.getInstance().getNodeRoot().reload();
		fullIndex();

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Mickey"),
				new PagingParameter().setPage(1).setPerPage(numAdditionalNodes + 1), new NodeRequestParameter().draft()));

		assertEquals("Check returned search results", numAdditionalNodes + 1, response.getData().size());
	}

	/**
	 * Tests if all tags are in the node response when searching for a node.
	 * 
	 * @throws JSONException
	 * @throws InterruptedException
	 */
	@Test
	public void testTagCount() throws JSONException, InterruptedException {
		fullIndex();
		Node node = content("concorde");
		int previousTagCount = node.getTags().size();
		//Create tags:
		int tagCount = 20;
		for (int i = 0; i < tagCount; i++) {
			TagResponse tagResponse = createTag(PROJECT_NAME, tagFamily("colors").getUuid(), "tag" + i);
			//Add tags to node:
			call(() -> getClient().addTagToNode(PROJECT_NAME, node.getUuid(), tagResponse.getUuid(), new NodeRequestParameter().draft()));
		}

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Concorde"), new NodeRequestParameter().draft()));
		assertEquals("Expect to only get one search result", 1, response.getMetainfo().getTotalCount());

		//assert tag count
		int nColorTags = response.getData().get(0).getTags().get("colors").getItems().size();
		int nBasicTags = response.getData().get(0).getTags().get("basic").getItems().size();
		assertEquals("Expect correct tag count", previousTagCount + tagCount, nColorTags + nBasicTags);
	}

	@Test
	public void testGlobalNodeSearch() throws Exception {
		fullIndex();

		NodeResponse oldNode = call(() -> getClient().findNodeByUuid(PROJECT_NAME, content("concorde").getUuid(),
				new NodeRequestParameter().draft()));

		ProjectCreateRequest createProject = new ProjectCreateRequest();
		createProject.setName("mynewproject");
		ProjectResponse projectResponse = call(() -> getClient().createProject(createProject));
		call(() -> getClient().addSchemaToProject(schemaContainer("folder").getUuid(), projectResponse.getUuid()));

		NodeCreateRequest createNode = new NodeCreateRequest();
		createNode.setLanguage("en");
		createNode.setSchema(new SchemaReference().setName("folder"));
		createNode.setParentNodeUuid(projectResponse.getRootNodeUuid());
		createNode.getFields().put("name", FieldUtil.createStringField("Concorde"));
		NodeResponse newNode = call(() -> getClient().createNode("mynewproject", createNode));

		// search in old project
		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("Concorde"), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result in " + PROJECT_NAME).usingElementComparatorOnFields("uuid").containsOnly(oldNode);

		// search in new project
		response = call(() -> getClient().searchNodes("mynewproject", getSimpleQuery("Concorde"), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result in mynewproject").usingElementComparatorOnFields("uuid").containsOnly(newNode);

		// search globally
		response = call(() -> getClient().searchNodes(getSimpleQuery("Concorde"), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Global search result").usingElementComparatorOnFields("uuid").containsOnly(newNode, oldNode);
	}

	@Test
	public void testGlobalPublishedNodeSearch() throws Exception {
		fullIndex();

		ProjectCreateRequest createProject = new ProjectCreateRequest();
		createProject.setName("mynewproject");
		ProjectResponse projectResponse = call(() -> getClient().createProject(createProject));
		call(() -> getClient().addSchemaToProject(schemaContainer("folder").getUuid(), projectResponse.getUuid()));

		NodeCreateRequest createNode = new NodeCreateRequest();
		createNode.setLanguage("en");
		createNode.setSchema(new SchemaReference().setName("folder"));
		createNode.setParentNodeUuid(projectResponse.getRootNodeUuid());
		createNode.getFields().put("name", FieldUtil.createStringField("Concorde"));
		NodeResponse newNode = call(() -> getClient().createNode("mynewproject", createNode));

		// search globally for published version
		NodeListResponse response = call(() -> getClient().searchNodes(getSimpleQuery("Concorde")));
		assertThat(response.getData()).as("Global search result before publishing").isEmpty();

		// publish the node
		call(() -> getClient().publishNode("mynewproject", newNode.getUuid()));

		// search globally for published version
		response = call(() -> getClient().searchNodes(getSimpleQuery("Concorde")));
		assertThat(response.getData()).as("Global search result after publishing").usingElementComparatorOnFields("uuid").containsOnly(newNode);
	}

	@Test
	public void testSearchPublishedNodes() throws Exception {
		String oldContent = "supersonic";
		String newContent = "urschnell";
		fullIndex();

		// "supersonic" not found in published nodes
		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(oldContent)));
		assertThat(response.getData()).as("Published search result").isEmpty();

		// publish the Concorde
		NodeResponse concorde = call(() -> getClient().findNodeByUuid(PROJECT_NAME, content("concorde").getUuid(), new NodeRequestParameter().draft()));
		call(() -> getClient().publishNode(PROJECT_NAME, content("concorde").getUuid()));

		// "urschnell" found in published nodes
		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(oldContent)));
		assertThat(response.getData()).as("Published search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);

		// change draft version of content
		NodeUpdateRequest update = new NodeUpdateRequest();
		update.setLanguage("en");
		update.getFields().put("content", FieldUtil.createHtmlField(newContent));
		call(() -> getClient().updateNode(PROJECT_NAME, concorde.getUuid(), update));

		// "supersonic" still found, "urschnell" not found in published nodes
		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(oldContent)));
		assertThat(response.getData()).as("Published search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);
		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(newContent)));
		assertThat(response.getData()).as("Published search result").isEmpty();

		// publish content "urschnell"
		call(() -> getClient().publishNode(PROJECT_NAME, content("concorde").getUuid()));

		// "supersonic" no longer found, but "urschnell" found in published nodes
		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(oldContent)));
		assertThat(response.getData()).as("Published search result").isEmpty();
		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(newContent)));
		assertThat(response.getData()).as("Published search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);
	}

	@Test
	public void testSearchDraftNodes() throws Exception {
		String oldContent = "supersonic";
		String newContent = "urschnell";
		fullIndex();

		NodeResponse concorde = call(() -> getClient().findNodeByUuid(PROJECT_NAME, content("concorde").getUuid(), new NodeRequestParameter().draft()));

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(oldContent), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(newContent), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result").isEmpty();

		// change draft version of content
		NodeUpdateRequest update = new NodeUpdateRequest();
		update.setLanguage("en");
		update.getFields().put("content", FieldUtil.createHtmlField(newContent));
		call(() -> getClient().updateNode(PROJECT_NAME, concorde.getUuid(), update));

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(oldContent), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result").isEmpty();

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery(newContent), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);
	}

	@Test
	public void testSearchPublishedInRelease() throws Exception {
		fullIndex();

		NodeResponse concorde = call(() -> getClient().findNodeByUuid(PROJECT_NAME, content("concorde").getUuid(),
				new NodeRequestParameter().draft()));
		call(() -> getClient().publishNode(PROJECT_NAME, concorde.getUuid()));

		ReleaseCreateRequest createRelease = new ReleaseCreateRequest();
		createRelease.setName("newrelease");
		call(() -> getClient().createRelease(PROJECT_NAME, createRelease));

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("supersonic")));
		assertThat(response.getData()).as("Search result").isEmpty();

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("supersonic"),
				new NodeRequestParameter().setRelease(project().getInitialRelease().getName())));
		assertThat(response.getData()).as("Search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);
	}

	@Test
	public void testSearchDraftInRelease() throws Exception {
		fullIndex();

		NodeResponse concorde = call(() -> getClient().findNodeByUuid(PROJECT_NAME, content("concorde").getUuid(),
				new NodeRequestParameter().draft()));

		ReleaseCreateRequest createRelease = new ReleaseCreateRequest();
		createRelease.setName("newrelease");
		call(() -> getClient().createRelease(PROJECT_NAME, createRelease));

		NodeListResponse response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("supersonic"), new NodeRequestParameter().draft()));
		assertThat(response.getData()).as("Search result").isEmpty();

		response = call(() -> getClient().searchNodes(PROJECT_NAME, getSimpleQuery("supersonic"),
				new NodeRequestParameter().setRelease(project().getInitialRelease().getName()).draft()));
		assertThat(response.getData()).as("Search result").usingElementComparatorOnFields("uuid").containsOnly(concorde);
	}

	private void addNumberSpeedField(int number) {
		Node node = content("concorde");

		Schema schema = node.getSchemaContainer().getLatestVersion().getSchema();
		schema.addField(new NumberFieldSchemaImpl().setName("speed"));
		node.getSchemaContainer().getLatestVersion().setSchema(schema);

		node.getGraphFieldContainer(english()).createNumber("speed").setNumber(number);
	}

	/**
	 * Add a micronode field to the tested content
	 */
	private void addMicronodeField() {
		Node node = content("concorde");

		Schema schema = node.getSchemaContainer().getLatestVersion().getSchema();
		MicronodeFieldSchemaImpl vcardFieldSchema = new MicronodeFieldSchemaImpl();
		vcardFieldSchema.setName("vcard");
		vcardFieldSchema.setAllowedMicroSchemas(new String[] { "vcard" });
		schema.addField(vcardFieldSchema);

		MicronodeGraphField vcardField = node.getGraphFieldContainer(english()).createMicronode("vcard",
				microschemaContainers().get("vcard").getLatestVersion());
		vcardField.getMicronode().createString("firstName").setString("Mickey");
		vcardField.getMicronode().createString("lastName").setString("Mouse");
	}

	/**
	 * Add a micronode list field to the tested content
	 */
	private void addMicronodeListField() {
		Node node = content("concorde");

		// Update the schema
		Schema schema = node.getSchemaContainer().getLatestVersion().getSchema();
		ListFieldSchema vcardListFieldSchema = new ListFieldSchemaImpl();
		vcardListFieldSchema.setName("vcardlist");
		vcardListFieldSchema.setListType("micronode");
		vcardListFieldSchema.setAllowedSchemas(new String[] { "vcard" });
		schema.addField(vcardListFieldSchema);

		// Set the mapping for the schema
		nodeIndexHandler.setNodeIndexMapping(schema.getName() + "-" + schema.getVersion(), schema).toBlocking().first();

		MicronodeGraphFieldList vcardListField = node.getGraphFieldContainer(english()).createMicronodeFieldList("vcardlist");
		for (Tuple<String, String> testdata : Arrays.asList(Tuple.tuple("Mickey", "Mouse"), Tuple.tuple("Donald", "Duck"))) {
			MicronodeField field = new MicronodeResponse();
			Micronode micronode = vcardListField.createMicronode(field);
			micronode.setMicroschemaContainerVersion(microschemaContainers().get("vcard").getLatestVersion());
			micronode.createString("firstName").setString(testdata.v1());
			micronode.createString("lastName").setString(testdata.v2());
		}
	}

	/**
	 * Generate the JSON for a searched in the nested field vcardlist
	 * 
	 * @param firstName
	 *            firstname to search for
	 * @param lastName
	 *            lastname to search for
	 * @return search JSON
	 * @throws IOException
	 */
	private String getNestedVCardListSearch(String firstName, String lastName) throws IOException {
		return XContentFactory.jsonBuilder().startObject().startObject("query").startObject("nested").field("path", "fields.vcardlist")
				.startObject("query").startObject("bool").startArray("must").startObject().startObject("match")
				.field("fields.vcardlist.fields.firstName", firstName).endObject().endObject().startObject().startObject("match")
				.field("fields.vcardlist.fields.lastName", lastName).endObject().endObject().endArray().endObject().endObject().endObject()
				.endObject().endObject().string();
	}
}
