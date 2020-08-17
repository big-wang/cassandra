package org.apache.cassandra.distributed.upgrade;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.junit.Assert;
import org.junit.Test;

import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.MapType;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.distributed.api.Feature;
import org.apache.cassandra.distributed.api.ICoordinator;
import org.apache.cassandra.distributed.api.QueryResults;
import org.apache.cassandra.distributed.api.SimpleQueryResult;
import org.apache.cassandra.distributed.shared.AssertUtils;
import org.apache.cassandra.distributed.shared.Versions;
import org.apache.cassandra.distributed.test.ThriftClientUtils;
import org.apache.cassandra.thrift.Deletion;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.utils.ByteBufferUtil;

public abstract class MigrateDropColumns extends UpgradeTestBase
{
    private static final MapType MAP_TYPE = MapType.getInstance(Int32Type.instance, Int32Type.instance, true);

    private final Versions.Major initial;
    private final Versions.Major[] upgrade;

    protected MigrateDropColumns(Versions.Major initial, Versions.Major... upgrade)
    {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.upgrade = Objects.requireNonNull(upgrade, "upgrade");
    }

    @Test
    public void dropColumns() throws Throwable
    {
        new TestCase()
        .upgrade(initial, upgrade)
        .withConfig(c -> c.with(Feature.NATIVE_PROTOCOL))
        .setup(cluster -> {
            cluster.schemaChange(withKeyspace("CREATE TABLE %s.tbl(pk int, tables map<int, int>, PRIMARY KEY (pk))"));

            ICoordinator coordinator = cluster.coordinator(1);

            // write a RT to pk=0
            ThriftClientUtils.thriftClient(cluster.get(1), thrift -> {
                thrift.set_keyspace(KEYSPACE);

                Mutation mutation = new Mutation();
                Deletion deletion = new Deletion();
                SlicePredicate slice = new SlicePredicate();
                SliceRange range = new SliceRange();
                range.setStart(CompositeType.build(ByteBufferUtil.bytes("tables")));
                range.setFinish(CompositeType.build(ByteBufferUtil.bytes("tables")));
                slice.setSlice_range(range);
                deletion.setPredicate(slice);
                deletion.setTimestamp(System.currentTimeMillis());
                mutation.setDeletion(deletion);

                thrift.batch_mutate(Collections.singletonMap(ByteBufferUtil.bytes(0),
                                                             Collections.singletonMap("tbl", Arrays.asList(mutation))),
                                    org.apache.cassandra.thrift.ConsistencyLevel.ALL);
            });

            // write table to pk=1
            // NOTE: because jvm-dtest doesn't support collections in the execute interface (see CASSANDRA-15969)
            // need to encode to a ByteBuffer first
            coordinator.execute(withKeyspace("INSERT INTO %s.tbl (pk, tables) VALUES (?, ?)"), ConsistencyLevel.ONE, 1, MAP_TYPE.decompose(ImmutableMap.of(1, 1)));

            cluster.forEach(inst -> inst.flush(KEYSPACE));

            cluster.schemaChange(withKeyspace("ALTER TABLE %s.tbl DROP tables"));
        })
        .runAfterClusterUpgrade(cluster -> {
            ICoordinator coordinator = cluster.coordinator(1);
            SimpleQueryResult qr = coordinator.executeWithResult("SELECT column_name " +
                                                                 "FROM system_schema.dropped_columns " +
                                                                 "WHERE keyspace_name=?" +
                                                                 " AND table_name=?;",
                                                                 ConsistencyLevel.ALL, KEYSPACE, "tbl");
            Assert.assertEquals(ImmutableSet.of("tables"), Sets.newHashSet(qr.map(r -> r.getString("column_name"))));

            assertRows(coordinator);

            // upgradesstables, make sure everything is still working
            cluster.forEach(n -> n.nodetoolResult("upgradesstables", KEYSPACE).asserts().success());

            assertRows(coordinator);
        })
        .run();
    }

    private static void assertRows(ICoordinator coordinator)
    {
        // since only a RT was written to this row there is no liveness information, so the row will be skipped
        AssertUtils.assertRows(
        coordinator.executeWithResult(withKeyspace("SELECT * FROM %s.tbl WHERE pk=?"), ConsistencyLevel.ALL, 0),
        QueryResults.empty());

        AssertUtils.assertRows(
        coordinator.executeWithResult(withKeyspace("SELECT * FROM %s.tbl WHERE pk=?"), ConsistencyLevel.ALL, 1),
        QueryResults.builder().row(1).build());
    }
}