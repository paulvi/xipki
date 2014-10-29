/*
 * Copyright (c) 2014 Lijun Liao
 *
 * TO-BE-DEFINE
 *
 */

package org.xipki.audit.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Lijun Liao
 */

public class AuditEvent
{

    /**
     * The name of the application the event belongs to.
     */
    private String applicationName;

    /**
     * The data array belonging to the event.
     */
    private final List<AuditEventData> eventDatas = new LinkedList<>();

    /**
     * The name of the event type.
     */
    private String name;

    /**
     * The AuditLevel this Event belongs to.
     */
    private AuditLevel level;

    /**
     * Timestamp when the event was saved.
     */
    private final Date timestamp;

    private AuditStatus status;

    private final List<ChildAuditEvent> childAuditEvents = new LinkedList<>();

    public AuditEvent(Date timestamp)
    {
        this.timestamp = (timestamp == null) ? new Date() : timestamp;
        this.level = AuditLevel.INFO;
    }

    public AuditLevel getLevel()
    {
        return level;
    }

    public void setLevel(AuditLevel level)
    {
        this.level = level;
    }

    public String getName()
    {
        return name;
    }

    public void setName(final String name)
    {
        this.name = name;
    }

    public String getApplicationName()
    {
        return applicationName;
    }

    public void setApplicationName(final String applicationName)
    {
        this.applicationName = applicationName;
    }

    public Date getTimestamp()
    {
        return timestamp;
    }

    public List<AuditEventData> getEventDatas()
    {
        return Collections.unmodifiableList(eventDatas);
    }

    public AuditEventData addEventData(AuditEventData eventData)
    {
        int idx = -1;
        for(int i = 0; i < eventDatas.size(); i++)
        {
            AuditEventData ed = eventDatas.get(i);
            if(ed.getName().equals(eventData.getName()))
            {
                idx = i;
                break;
            }
        }

        AuditEventData ret = null;
        if(idx != -1)
        {
            ret = eventDatas.get(idx);
        }
        eventDatas.add(eventData);

        for(ChildAuditEvent cae : childAuditEvents)
        {
            cae.removeEventData(eventData.getName());
        }

        return ret;
    }

    public AuditStatus getStatus()
    {
        return status;
    }

    public void setStatus(AuditStatus status)
    {
        this.status = status;
    }

    public void addChildAuditEvent(ChildAuditEvent childAuditEvent)
    {
        childAuditEvents.add(childAuditEvent);
    }

    public boolean containsChildAuditEvents()
    {
        return childAuditEvents.isEmpty() == false;
    }

    public List<AuditEvent> expandAuditEvents()
    {
        int size = childAuditEvents.size();
        if(size == 0)
        {
            return Arrays.asList(this);
        }

        List<AuditEvent> expandedEvents = new ArrayList<>(size);
        for(ChildAuditEvent child : childAuditEvents)
        {
            AuditEvent event = new AuditEvent(timestamp);
            event.setApplicationName(applicationName);
            event.setName(name);

            if(child.getLevel() != null)
            {
                event.setLevel(child.getLevel());
            }
            else
            {
                event.setLevel(level);
            }

            if(child.getStatus() != null)
            {
                event.setStatus(child.getStatus());
            }
            else
            {
                event.setStatus(status);
            }

            for(AuditEventData eventData : eventDatas)
            {
                event.addEventData(eventData);
            }

            for(AuditEventData eventData : child.getEventDatas())
            {
                event.addEventData(eventData);
            }

            expandedEvents.add(event);
        }

        return expandedEvents;
    }
}
