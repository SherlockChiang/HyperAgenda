package com.vince.hyperagenda.xposed;

final class AgendaEvent {
    final long id;
    final String title;
    final String location;
    final long begin;
    final long end;
    final boolean allDay;

    AgendaEvent(long id, String title, String location, long begin, long end,
                boolean allDay) {
        this.id = id;
        this.title = title;
        this.location = location;
        this.begin = begin;
        this.end = end;
        this.allDay = allDay;
    }
}
