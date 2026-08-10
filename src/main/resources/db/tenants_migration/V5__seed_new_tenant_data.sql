-- seed_connection_types.sql
-- Sample records for the connection_types table

INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'github', 'github', '{
  "type": "object",
  "required": [
    "access_token"
  ],
  "properties": {
    "repo": {
      "type": "string",
      "description": "Default repository name"
    },
    "owner": {
      "type": "string",
      "description": "Default repository owner/org"
    },
    "access_token": {
      "type": "string",
      "description": "Personal access token or OAuth token"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'GitHub');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'slack', 'slack', '{
  "type": "object",
  "required": [
    "access_token",
    "workspace_id"
  ],
  "properties": {
    "channel": {
      "type": "string",
      "description": "Default channel to post to"
    },
    "access_token": {
      "type": "string",
      "description": "OAuth access token"
    },
    "workspace_id": {
      "type": "string",
      "description": "Slack workspace ID"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'Slack');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'postgres', 'postgres', '{
  "type": "object",
  "required": [
    "host",
    "database",
    "username",
    "password"
  ],
  "properties": {
    "ssl": {
      "type": "boolean",
      "default": false
    },
    "host": {
      "type": "string"
    },
    "port": {
      "type": "integer",
      "default": 5432
    },
    "database": {
      "type": "string"
    },
    "password": {
      "type": "password"
    },
    "username": {
      "type": "string"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'PostgreSQL');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'sftp', 'generic', '{
  "type": "object",
  "required": [
    "host",
    "username"
  ],
  "properties": {
    "host": {
      "type": "string"
    },
    "port": {
      "type": "integer",
      "default": 22
    },
    "password": {
      "type": "password"
    },
    "username": {
      "type": "string"
    },
    "root_path": {
      "type": "string",
      "default": "/"
    },
    "passphrase": {
      "type": "string"
    },
    "private_key": {
      "type": "string",
      "description": "PEM-encoded private key"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'SFTP');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'smtp', 'email', '{
  "type": "object",
  "required": [
    "host",
    "port",
    "username",
    "password"
  ],
  "properties": {
    "host": {
      "type": "string"
    },
    "port": {
      "type": "integer",
      "default": 587
    },
    "secure": {
      "type": "boolean",
      "default": true
    },
    "password": {
      "type": "password"
    },
    "username": {
      "type": "string"
    },
    "from_address": {
      "type": "string",
      "format": "email"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'SMTP Email');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'redis', 'redis', '{
  "type": "object",
  "required": [
    "host"
  ],
  "properties": {
    "tls": {
      "type": "boolean",
      "default": false,
      "description": "Connect using TLS"
    },
    "host": {
      "type": "string"
    },
    "port": {
      "type": "integer",
      "default": 6379
    },
    "database": {
      "type": "integer",
      "default": 0
    },
    "password": {
      "type": "password"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'Redis');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'mysql', 'mysql', '{
  "type": "object",
  "required": [
    "host",
    "database",
    "username",
    "password"
  ],
  "properties": {
    "ssl": {
      "type": "boolean",
      "default": false
    },
    "host": {
      "type": "string"
    },
    "port": {
      "type": "integer",
      "default": 5432
    },
    "database": {
      "type": "string"
    },
    "password": {
      "type": "password"
    },
    "username": {
      "type": "string"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'MySql');
INSERT INTO connection_types (id, type, provider, schema, created_at, name)
VALUES (gen_random_uuid(), 'single_header', 'header', '{
  "type": "object",
  "required": [
    "key",
    "value"
  ],
  "properties": {
    "key": {
      "type": "string"
    },
    "value": {
      "type": "string"
    }
  }
}', '2026-08-09 03:39:51.550316 +00:00', 'Http Header');
