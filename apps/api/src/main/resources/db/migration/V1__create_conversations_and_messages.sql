create table conversations (
    id uuid primary key default gen_random_uuid(),
    title varchar(120) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table messages (
    id bigint generated always as identity primary key,
    conversation_id uuid not null references conversations (id) on delete cascade,
    role varchar(16) not null,
    content text not null,
    status varchar(16) not null default 'completed',
    created_at timestamptz not null default now(),

    constraint messages_role_check
        check (role in ('user', 'assistant')),
    constraint messages_status_check
        check (status in ('pending', 'completed', 'failed', 'cancelled'))
);

create index messages_conversation_timeline_idx
    on messages (conversation_id, created_at, id);

create index conversations_updated_at_idx
    on conversations (updated_at desc)