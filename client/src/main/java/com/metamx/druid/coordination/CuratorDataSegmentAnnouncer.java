/*
 * Druid - a distributed column store.
 * Copyright (C) 2012  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.metamx.druid.coordination;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.common.logger.Logger;
import com.metamx.druid.client.DataSegment;
import com.metamx.druid.curator.announcement.Announcer;
import com.metamx.druid.initialization.ZkPathsConfig;
import org.apache.curator.utils.ZKPaths;

import java.io.IOException;

public class CuratorDataSegmentAnnouncer implements DataSegmentAnnouncer
{
  private static final Logger log = new Logger(CuratorDataSegmentAnnouncer.class);

  private final Object lock = new Object();

  private final DruidServerMetadata server;
  private final ZkPathsConfig config;
  private final Announcer announcer;
  private final ObjectMapper jsonMapper;
  private final String servedSegmentsLocation;

  private volatile boolean started = false;

  public CuratorDataSegmentAnnouncer(
      DruidServerMetadata server,
      ZkPathsConfig config,
      Announcer announcer,
      ObjectMapper jsonMapper
  )
  {
    this.server = server;
    this.config = config;
    this.announcer = announcer;
    this.jsonMapper = jsonMapper;
    this.servedSegmentsLocation = ZKPaths.makePath(config.getServedSegmentsPath(), server.getName());
  }

  @LifecycleStart
  public void start()
  {
    synchronized (lock) {
      if (started) {
        return;
      }

      try {
        final String path = makeAnnouncementPath();
        log.info("Announcing self[%s] at [%s]", server, path);
        announcer.announce(path, jsonMapper.writeValueAsBytes(server));
      }
      catch (JsonProcessingException e) {
        throw Throwables.propagate(e);
      }

      started = true;
    }
  }

  @LifecycleStop
  public void stop()
  {
    synchronized (lock) {
      if (!started) {
        return;
      }

      log.info("Stopping CuratorDataSegmentAnnouncer with config[%s]", config);
      announcer.unannounce(makeAnnouncementPath());

      started = false;
    }
  }

  public void announceSegment(DataSegment segment) throws IOException
  {
    final String path = makeServedSegmentPath(segment);
    log.info("Announcing segment[%s] to path[%s]", segment.getIdentifier(), path);
    announcer.announce(path, jsonMapper.writeValueAsBytes(segment));
  }

  public void unannounceSegment(DataSegment segment) throws IOException
  {
    final String path = makeServedSegmentPath(segment);
    log.info("Unannouncing segment[%s] at path[%s]", segment.getIdentifier(), path);
    announcer.unannounce(path);
  }

  private String makeAnnouncementPath() {
    return ZKPaths.makePath(config.getAnnouncementsPath(), server.getName());
  }

  private String makeServedSegmentPath(DataSegment segment)
  {
    return ZKPaths.makePath(servedSegmentsLocation, segment.getIdentifier());
  }
}
