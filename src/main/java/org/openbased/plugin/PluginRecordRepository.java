package org.openbased.plugin;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PluginRecordRepository extends JpaRepository<PluginRecord, String> {

    List<PluginRecord> findByEnabledTrue();
}
