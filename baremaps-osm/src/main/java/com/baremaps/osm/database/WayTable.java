/*
 * Copyright (C) 2011 The Baremaps Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baremaps.osm.database;

import com.baremaps.osm.database.WayTable.Way;
import com.baremaps.osm.geometry.GeometryUtil;
import com.baremaps.osm.geometry.WayBuilder;
import com.baremaps.osm.store.Store;
import com.baremaps.osm.store.StoreException;
import com.baremaps.core.postgis.CopyWriter;
import com.baremaps.osm.model.Info;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;

public class WayTable implements Table<Way> {

  public static class Way {

    private final long id;

    private final int version;

    private final LocalDateTime timestamp;

    private final long changeset;

    private final int userId;

    private final Map<String, String> tags;

    private final List<Long> nodes;

    private final Geometry geometry;

    public Way(
        long id,
        int version,
        LocalDateTime timestamp,
        long changeset,
        int userId,
        Map<String, String> tags,
        List<Long> nodes,
        Geometry geometry) {
      this.id = id;
      this.version = version;
      this.timestamp = timestamp;
      this.changeset = changeset;
      this.userId = userId;
      this.tags = tags;
      this.nodes = nodes;
      this.geometry = geometry;
    }

    public long getId() {
      return id;
    }

    public int getVersion() {
      return version;
    }

    public LocalDateTime getTimestamp() {
      return timestamp;
    }

    public long getChangeset() {
      return changeset;
    }

    public int getUserId() {
      return userId;
    }

    public Map<String, String> getTags() {
      return tags;
    }

    public List<Long> getNodes() {
      return nodes;
    }

    public Geometry getGeometry() {
      return geometry;
    }

  }

  private static final String SELECT =
      "SELECT version, uid, timestamp, changeset, tags, nodes, geom FROM osm_ways WHERE id = ?";

  private static final String SELECT_IN =
      "SELECT id, version, uid, timestamp, changeset, tags, nodes FROM osm_ways WHERE id = ANY (?)";

  private static final String INSERT =
      "INSERT INTO osm_ways (id, version, uid, timestamp, changeset, tags, nodes, geom) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT (id) DO UPDATE SET "
          + "version = excluded.version, "
          + "uid = excluded.uid, "
          + "timestamp = excluded.timestamp, "
          + "changeset = excluded.changeset, "
          + "tags = excluded.tags, "
          + "nodes = excluded.nodes, "
          + "geom = excluded.geom";

  private static final String DELETE = "DELETE FROM osm_ways WHERE id = ?";

  private static final String COPY =
      "COPY osm_ways (id, version, uid, timestamp, changeset, tags, nodes, geom) FROM STDIN BINARY";

  private final DataSource dataSource;

  public WayTable(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public WayTable.Way get(Long id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT)) {
      statement.setLong(1, id);
      ResultSet result = statement.executeQuery();
      if (result.next()) {
        int version = result.getInt(1);
        int uid = result.getInt(2);
        LocalDateTime timestamp = result.getObject(3, LocalDateTime.class);
        long changeset = result.getLong(4);
        Map<String, String> tags = (Map<String, String>) result.getObject(5);
        List<Long> nodes = new ArrayList<>();
        Array array = result.getArray(6);
        if (array != null) {
          nodes = Arrays.asList((Long[]) array.getArray());
        }
        Geometry geometry = GeometryUtil.deserialize(result.getBytes(7));
        return new WayTable.Way(id, version, timestamp, changeset, uid, tags, nodes, geometry);
      } else {
        throw new IllegalArgumentException();
      }
    } catch (SQLException e) {
      throw new StoreException(e);
    }
  }

  @Override
  public List<Way> getAll(List<Long> keys) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_IN)) {
      statement.setArray(1, connection.createArrayOf("int8", keys.toArray()));
      ResultSet result = statement.executeQuery();
      Map<Long, Way> ways = new HashMap<>();
      while (result.next()) {
        long id = result.getLong(1);
        int version = result.getInt(2);
        int uid = result.getInt(3);
        LocalDateTime timestamp = result.getObject(4, LocalDateTime.class);
        long changeset = result.getLong(5);
        Map<String, String> tags = (Map<String, String>) result.getObject(6);
        List<Long> nodes = new ArrayList<>();
        Array array = result.getArray(7);
        if (array != null) {
          nodes = Arrays.asList((Long[]) array.getArray());
        }
        Geometry geometry = GeometryUtil.deserialize(result.getBytes(8));
        ways.put(id, new Way(id, version, timestamp, changeset, uid, tags, nodes, geometry));
      }
      return keys.stream().map(key -> ways.get(key)).collect(Collectors.toList());
    } catch (SQLException e) {
      throw new StoreException(e);
    }
  }

  public void put(Way value) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setLong(1, value.getId());
      statement.setInt(2, value.getVersion());
      statement.setInt(3, value.getUserId());
      statement.setObject(4, value.getTimestamp());
      statement.setLong(5, value.getChangeset());
      statement.setObject(6, value.getTags());
      statement.setObject(7, value.getNodes().stream().mapToLong(Long::longValue).toArray());
      statement.setBytes(8, GeometryUtil.serialize(value.getGeometry()));
      statement.execute();
    } catch (SQLException e) {
      throw new StoreException(e);
    }
  }

  @Override
  public void putAll(List<Way> entries) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT)) {
      for (Way entry : entries) {
        statement.clearParameters();
        statement.setLong(1, entry.getId());
        statement.setInt(2, entry.getVersion());
        statement.setInt(3, entry.getUserId());
        statement.setObject(4, entry.getTimestamp());
        statement.setLong(5, entry.getChangeset());
        statement.setObject(6, entry.getTags());
        statement.setObject(7, entry.getNodes().stream().mapToLong(Long::longValue).toArray());
        statement.setBytes(8, GeometryUtil.serialize(entry.getGeometry()));
        statement.addBatch();
      }
      statement.executeBatch();
    } catch (SQLException e) {
      throw new StoreException(e);
    }
  }

  public void delete(Long key) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(DELETE)) {
      statement.setLong(1, key);
      statement.execute();
    } catch (SQLException e) {
      throw new StoreException(e);
    }
  }

  @Override
  public void deleteAll(List<Long> keys) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(DELETE)) {
      for (Long key : keys) {
        statement.clearParameters();
        statement.setLong(1, key);
        statement.execute();
      }
      statement.executeBatch();
    } catch (SQLException e) {
      throw new StoreException(e);
    }
  }

  public void importAll(List<Way> entries) {
    try (Connection connection = dataSource.getConnection()) {
      PGConnection pgConnection = connection.unwrap(PGConnection.class);
      try (CopyWriter writer = new CopyWriter(new PGCopyOutputStream(pgConnection, COPY))) {
        writer.writeHeader();
        for (Way entry : entries) {
          writer.startRow(8);
          writer.writeLong(entry.getId());
          writer.writeInteger(entry.getVersion());
          writer.writeInteger(entry.getUserId());
          writer.writeLocalDateTime(entry.getTimestamp());
          writer.writeLong(entry.getChangeset());
          writer.writeHstore(entry.getTags());
          writer.writeLongList(entry.getNodes());
          writer.writeGeometry(entry.getGeometry());
        }
      }
    } catch (Exception e) {
      throw new StoreException(e);
    }
  }

}
