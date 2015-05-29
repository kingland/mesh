package com.gentics.mesh.core.data.service.generic;

import java.util.List;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.stereotype.Component;

import com.gentics.mesh.core.data.model.generic.GenericNode;
import com.gentics.mesh.core.repository.I18NValueRepository;
import com.gentics.mesh.core.repository.generic.GenericNodeRepository;
import com.gentics.mesh.etc.neo4j.UUIDTransactionEventHandler;
import com.gentics.mesh.util.UUIDUtil;

@Component
public class GenericNodeServiceImpl<T extends GenericNode> implements GenericNodeService<T> {

	@Autowired
	protected I18NValueRepository i18nPropertyRepository;
	
	@Autowired
	@Qualifier("genericNodeRepository")
	protected GenericNodeRepository<T> nodeRepository;

	@Autowired
	protected GraphDatabaseService database;

	@Autowired
	protected Neo4jTemplate neo4jTemplate;

	@Override
	public T save(T node) {
		if (node.isNew() && node.getUuid() == null) {
			node.setUuid(UUIDUtil.getUUID());
		}
		return nodeRepository.save(node);
	}

	@Override
	public void delete(T node) {
		nodeRepository.delete(node);
	}

	@Override
	public T findOne(Long id) {
		return nodeRepository.findOne(id);
	}

	@Override
	public void save(List<T> nodes) {
		this.nodeRepository.save(nodes);
	}


	@Override
	public T findByName(String project, String name) {
		return nodeRepository.findByI18Name(project, name);
	}

	@Override
	public T findByUUID(String project, String uuid) {
		return nodeRepository.findByUUID(project, uuid);
	}

	@Override
	public T reload(T node) {
		return nodeRepository.findOne(node.getId());
	}

	@Override
	public T findByUUID(String uuid) {
		return nodeRepository.findByUUID(uuid);

	}

	@Override
	public void deleteByUUID(String uuid) {
		nodeRepository.deleteByUuid(uuid);
	}

	@Override
	public T projectTo(Node node, Class<T> clazz) {
		T entity = neo4jTemplate.projectTo(node, clazz);
		return entity;
	}
}
