package com.owera.xaps.dbi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import com.owera.common.db.ConnectionProvider;
import com.owera.common.db.NoAvailableConnectionException;
import com.owera.common.log.Logger;
import com.owera.xaps.dbi.InsertOrUpdateStatement.Field;
import com.owera.xaps.dbi.SyslogEvent.StorePolicy;

import com.owera.xaps.dbi.util.XAPSVersionCheck;

public class SyslogEvents {
	public static int MAX_GENERATE_ON_ABSENCE_TIMEOUT_HOURS = 48;
	private static Logger logger = new Logger();
	private static Map<Integer, SyslogEvent> idMap = new TreeMap<Integer, SyslogEvent>();
	static {
		SyslogEvent defaultEvent = new SyslogEvent();
		defaultEvent.validateInput(false);
		defaultEvent.setEventId(0);
		defaultEvent.setName("Default");
		defaultEvent.setDescription("Default event");
		defaultEvent.setDeleteLimit(0);
		idMap.put(0, defaultEvent);
	}
	private Map<Integer, SyslogEvent> eventIdMap;
	private Unittype unittype;

	public SyslogEvents(TreeMap<Integer, SyslogEvent> eventIdMap, Unittype unittype) {
		this.eventIdMap = eventIdMap;
		for (SyslogEvent event : eventIdMap.values())
			idMap.put(event.getId(), event);
		this.unittype = unittype;
	}
	
	protected static void updateIdMap(SyslogEvent syslogEvent) {
		idMap.put(syslogEvent.getId(), syslogEvent);
	}

	public static SyslogEvent getById(Integer id) {
		return idMap.get(id);
	}

	public SyslogEvent getByEventId(Integer id) {
		return eventIdMap.get(id);
	}

	public SyslogEvent[] getSyslogEvents() {
		SyslogEvent[] syslogEvents = new SyslogEvent[eventIdMap.size()];
		eventIdMap.values().toArray(syslogEvents);
		return syslogEvents;
	}

	@Override
	public String toString() {
		return "Contains " + idMap.size() + " syslog events";
	}

	private void addOrChangeSyslogEventImpl(SyslogEvent syslogEvent, XAPS xaps) throws SQLException, NoAvailableConnectionException {
		Connection c = ConnectionProvider.getConnection(xaps.connectionProperties, true);
		SQLException sqlex = null;
		PreparedStatement ps = null;
		try {
			InsertOrUpdateStatement ious = new InsertOrUpdateStatement("syslog_event", new Field("id", syslogEvent.getId()));
			ious.addField(new Field("syslog_event_id", syslogEvent.getEventId()));
			ious.addField(new Field("syslog_event_name", syslogEvent.getName()));
			ious.addField(new Field("description", syslogEvent.getDescription()));
			ious.addField(new Field("expression", syslogEvent.getExpression().toString()));
			ious.addField(new Field("delete_limit", syslogEvent.getDeleteLimit()));
			if (XAPSVersionCheck.syslogEventReworkSupported) {
				ious.addField(new Field("unit_type_id", syslogEvent.getUnittype().getId()));
				ious.addField(new Field("store_policy", syslogEvent.getStorePolicy().toString()));
				ious.addField(new Field("filestore_id", syslogEvent.getScript() == null ? null : syslogEvent.getScript().getId()));
				ious.addField(new Field("group_id", syslogEvent.getGroup() == null ? null : syslogEvent.getGroup().getId()));
			} else {
				ious.addField(new Field("unit_type_name", syslogEvent.getUnittype().getName()));
				if (syslogEvent.getStorePolicy() == StorePolicy.DUPLICATE) // Normalize the counter to 60 minutes in old databases
					ious.addField(new Field("task", StorePolicy.DUPLICATE + "" + SyslogEvent.DUPLICATE_TIMEOUT));
				else
					ious.addField(new Field("task", syslogEvent.getStorePolicy()));
			}
			ps = ious.makePreparedStatement(c);
			ps.setQueryTimeout(60);
			ps.executeUpdate();
			if (ious.isInsert()) {
				ResultSet gk = ps.getGeneratedKeys();
				if (gk.next())
					syslogEvent.setId(gk.getInt(1));
				logger.notice("Inserted syslog event " + syslogEvent.getEventId());
				if (xaps.getDbi() != null)
					xaps.getDbi().publishAdd(syslogEvent, syslogEvent.getUnittype());
			} else {
				logger.notice("Updated syslog event " + syslogEvent.getEventId());
				if (xaps.getDbi() != null)
					xaps.getDbi().publishChange(syslogEvent, unittype);
			}
		} catch (SQLException sqle) {
			sqlex = sqle;
			throw sqle;
		} finally {
			if (ps != null)
				ps.close();
			if (c != null)
				ConnectionProvider.returnConnection(c, sqlex);
		}
	}

	public void addOrChangeSyslogEvent(SyslogEvent syslogEvent, XAPS xaps) throws SQLException, NoAvailableConnectionException {
		if (!xaps.getUser().isUnittypeAdmin(unittype.getId()))
			throw new IllegalArgumentException("Not allowed action for this user");
		syslogEvent.validate();
		addOrChangeSyslogEventImpl(syslogEvent, xaps);
		idMap.put(syslogEvent.getId(), syslogEvent);
		eventIdMap.put(syslogEvent.getEventId(), syslogEvent);
	}

	private void deleteSyslogEventImpl(Unittype unittype, SyslogEvent syslogEvent, XAPS xaps) throws SQLException, NoAvailableConnectionException {
		PreparedStatement ps = null;
		Connection c = ConnectionProvider.getConnection(xaps.connectionProperties, true);
		SQLException sqlex = null;
		try {
			DynamicStatement ds = new DynamicStatement();
			if (XAPSVersionCheck.syslogEventReworkSupported)
				ds.addSqlAndArguments("DELETE FROM syslog_event WHERE syslog_event_id = ? ", syslogEvent.getEventId());
			if (XAPSVersionCheck.syslogEventReworkSupported)
				ds.addSqlAndArguments("AND unit_type_id = ?", unittype.getId());
			else
				ds.addSqlAndArguments("AND unit_type_name = ?", unittype.getName());
			ps = ds.makePreparedStatement(c);
			ps.setQueryTimeout(60);
			ps.executeUpdate();
			
			logger.notice("Deleted syslog event " + syslogEvent.getEventId());
			if (xaps.getDbi() != null)
				xaps.getDbi().publishDelete(syslogEvent, unittype);
		} catch (SQLException sqle) {
			sqlex = sqle;
			throw sqle;
		} finally {
			if (ps != null)
				ps.close();
			if (c != null)
				ConnectionProvider.returnConnection(c, sqlex);
		}
	}

	/**
	 * The first time this method is run, the flag is set. The second time this
	 * method is run, the parameter is removed from the name- and id-Map.
	 * 
	 * @throws NoAvailableConnectionException
	 * @throws SQLException
	 */
	public void deleteSyslogEvent(SyslogEvent syslogEvent, XAPS xaps) throws SQLException, NoAvailableConnectionException {
		if (!xaps.getUser().isUnittypeAdmin(unittype.getId()))
			throw new IllegalArgumentException("Not allowed action for this user");
		if (syslogEvent.getEventId() < 1000)
			throw new IllegalArgumentException("Cannot delete syslog events with id 0-999, they are restricted to xAPS");
		deleteSyslogEventImpl(syslogEvent, xaps);
	}

	protected void deleteSyslogEventImpl(SyslogEvent syslogEvent, XAPS xaps) throws SQLException, NoAvailableConnectionException {
		deleteSyslogEventImpl(unittype, syslogEvent, xaps);
		idMap.remove(syslogEvent.getId());
		eventIdMap.remove(syslogEvent.getEventId());
	}

}
