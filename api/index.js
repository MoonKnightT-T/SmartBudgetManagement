const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
let mysql = null;
try {
  mysql = require('mysql2/promise');
} catch {}

let blob = null;
try {
  blob = require('@vercel/blob');
} catch {}

const SESSION_SECRET = process.env.SESSION_SECRET || 'smart-budget-production-secret-key-2026';

// --- Cryptography & Session Helpers ---
function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.pbkdf2Sync(password, salt, 65536, 32, 'sha256');
  return `${salt.toString('base64')}:${hash.toString('base64')}`;
}

function verifyPassword(password, stored) {
  const parts = stored.split(':');
  if (parts.length !== 2) return false;
  const salt = Buffer.from(parts[0], 'base64');
  const expected = Buffer.from(parts[1], 'base64');
  const actual = crypto.pbkdf2Sync(password, salt, 65536, 32, 'sha256');
  return crypto.timingSafeEqual(expected, actual);
}

function signSession(userId, name) {
  const payload = Buffer.from(JSON.stringify({ userId, name })).toString('base64url');
  const sig = crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
  return `${payload}.${sig}`;
}

function verifySession(token) {
  if (!token) return null;
  const dot = token.lastIndexOf('.');
  if (dot === -1) return null;
  const payload = token.substring(0, dot);
  const sig = token.substring(dot + 1);
  const expected = crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
  if (sig !== expected) return null;
  try {
    const data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!data.userId || !data.name) return null;
    return data;
  } catch {
    return null;
  }
}

function parseCookies(cookieHeader) {
  const cookies = {};
  if (!cookieHeader) return cookies;
  cookieHeader.split(';').forEach(cookie => {
    const parts = cookie.split('=');
    if (parts.length >= 2) {
      cookies[parts[0].trim()] = decodeURIComponent(parts.slice(1).join('=').trim());
    }
  });
  return cookies;
}

function getTodayString() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// --- Initial Seed & Store ---
let initialData = { users: [], categories: [], transactions: [], budgets: [], goals: [] };
try {
  const seedFile = path.join(__dirname, '..', 'seed-data.json');
  if (fs.existsSync(seedFile)) {
    initialData = JSON.parse(fs.readFileSync(seedFile, 'utf8'));
  }
} catch (e) {
  console.error('Failed to load seed-data.json:', e);
}

const store = {
  users: initialData.users || [],
  categories: initialData.categories || [],
  transactions: initialData.transactions || [],
  budgets: initialData.budgets || [],
  goals: initialData.goals || [],
  nextUserId: Math.max(1, ...((initialData.users || []).map(u => u.user_id + 1))),
  nextCatId: Math.max(100, ...((initialData.categories || []).map(c => c.category_id + 1))),
  nextTxId: Math.max(1, ...((initialData.transactions || []).map(t => t.transaction_id + 1))),
  nextBudgetId: Math.max(1, ...((initialData.budgets || []).map(b => b.budget_id + 1))),
  nextGoalId: Math.max(1, ...((initialData.goals || []).map(g => g.goal_id + 1)))
};

let isBlobLoaded = false;
async function ensureBlobLoaded() {
  if (isBlobLoaded) return;
  if (blob && (process.env.BLOB_STORE_ID || process.env.BLOB_READ_WRITE_TOKEN)) {
    try {
      const result = await blob.get('smart_budget_data.json', { access: 'private' });
      if (result && result.stream) {
        const chunks = [];
        for await (const chunk of result.stream) {
          chunks.push(chunk);
        }
        const text = Buffer.concat(chunks).toString('utf8');
        const data = JSON.parse(text);
        if (data.users && data.users.length) store.users = data.users;
        if (data.categories && data.categories.length) store.categories = data.categories;
        if (data.transactions) store.transactions = data.transactions;
        if (data.budgets) store.budgets = data.budgets;
        if (data.goals) store.goals = data.goals;
        if (data.nextUserId) store.nextUserId = data.nextUserId;
        if (data.nextCatId) store.nextCatId = data.nextCatId;
        if (data.nextTxId) store.nextTxId = data.nextTxId;
        if (data.nextBudgetId) store.nextBudgetId = data.nextBudgetId;
        if (data.nextGoalId) store.nextGoalId = data.nextGoalId;
      }
    } catch (e) {
      console.warn('Blob read warning:', e.message);
    }
  }
  isBlobLoaded = true;
}

async function persistBlob() {
  if (blob && (process.env.BLOB_STORE_ID || process.env.BLOB_READ_WRITE_TOKEN)) {
    try {
      const data = {
        users: store.users,
        categories: store.categories,
        transactions: store.transactions,
        budgets: store.budgets,
        goals: store.goals,
        nextUserId: store.nextUserId,
        nextCatId: store.nextCatId,
        nextTxId: store.nextTxId,
        nextBudgetId: store.nextBudgetId,
        nextGoalId: store.nextGoalId
      };
      await blob.put('smart_budget_data.json', JSON.stringify(data), { access: 'private', addRandomSuffix: false, allowOverwrite: true });
    } catch (e) {
      console.warn('Blob persist warning:', e.message);
    }
  }
}

// --- Optional MySQL Pool ---
let dbPool = null;
function getDbPool() {
  if (dbPool) return dbPool;
  const dbUrl = process.env.MYSQL_URL || process.env.DATABASE_URL;
  const dbHost = process.env.DB_HOST;
  if (!mysql) return null;
  if (dbUrl) {
    dbPool = mysql.createPool(dbUrl);
    return dbPool;
  }
  if (dbHost) {
    dbPool = mysql.createPool({
      host: dbHost,
      port: parseInt(process.env.DB_PORT || '3306', 10),
      user: process.env.DB_USER || 'root',
      password: process.env.DB_PASSWORD || '',
      database: process.env.DB_NAME || 'smart_budget_db',
      waitForConnections: true,
      connectionLimit: 10
    });
    return dbPool;
  }
  return null;
}

async function parseBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  if (req.body && typeof req.body === 'string') {
    if (req.body.startsWith('{') || req.body.startsWith('[')) {
      try { return JSON.parse(req.body); } catch {}
    }
    const params = new URLSearchParams(req.body);
    const obj = {};
    for (const [k, v] of params.entries()) obj[k] = v;
    return obj;
  }
  return new Promise(resolve => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        if (!body) return resolve({});
        if (body.startsWith('{') || body.startsWith('[')) {
          return resolve(JSON.parse(body));
        }
        const params = new URLSearchParams(body);
        const obj = {};
        for (const [k, v] of params.entries()) obj[k] = v;
        resolve(obj);
      } catch {
        resolve({});
      }
    });
  });
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');

  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = parsedUrl.pathname;
  if (req.query && req.query.path) {
    pathname = '/' + (Array.isArray(req.query.path) ? req.query.path.join('/') : req.query.path);
  } else if (pathname.startsWith('/api')) {
    pathname = pathname.substring(4);
  }
  if (!pathname.startsWith('/')) pathname = '/' + pathname;

  if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
    let file = path.join(__dirname, '..', 'public', 'index.html');
    if (!fs.existsSync(file)) {
      file = path.join(__dirname, '..', 'web', 'index.html');
    }
    if (fs.existsSync(file)) {
      res.statusCode = 200;
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      return res.end(fs.readFileSync(file));
    }
  }

  // Ensure persistent data is loaded from blob storage if available
  await ensureBlobLoaded();

  const query = Object.fromEntries(parsedUrl.searchParams.entries());
  let body = {};
  if (req.method === 'POST') {
    body = await parseBody(req);
  }
  const params = { ...query, ...body };

  const cookies = parseCookies(req.headers.cookie);
  const sessionToken = cookies['sid'];
  const session = verifySession(sessionToken);

  function json(data, status = 200) {
    res.statusCode = status;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.end(JSON.stringify(data));
  }

  function error(msg, status = 400) {
    json({ error: msg }, status);
  }

  function requireAuth() {
    if (!session) {
      error('Please log in', 401);
      return false;
    }
    return true;
  }

  const endpoint = `${req.method} ${pathname}`;
  const pool = getDbPool();

  try {
    switch (endpoint) {
      case 'POST /register': {
        const name = (params.name || '').trim();
        const email = (params.email || '').trim().toLowerCase();
        const password = params.password || '';

        if (!name) return error('Name is required');
        if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
          return error('Please enter a valid email address');
        }
        if (password.length < 6) {
          return error('Password must be at least 6 characters');
        }

        const password_hash = hashPassword(password);

        if (pool) {
          try {
            const [existing] = await pool.execute('SELECT user_id FROM users WHERE email = ?', [email]);
            if (existing.length > 0) return error('An account with this email already exists', 409);
            const [insertRes] = await pool.execute('INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)', [name, email, password_hash]);
            const token = signSession(insertRes.insertId, name);
            res.setHeader('Set-Cookie', `sid=${token}; Path=/; HttpOnly; SameSite=Strict`);
            return json({ name });
          } catch (err) {
            console.error('MySQL register error:', err);
            return error('Database error during registration', 500);
          }
        }

        const existing = store.users.find(u => u.email === email);
        if (existing) return error('An account with this email already exists', 409);

        const user = {
          user_id: store.nextUserId++,
          name,
          email,
          password_hash
        };
        store.users.push(user);
        await persistBlob();

        const token = signSession(user.user_id, user.name);
        res.setHeader('Set-Cookie', `sid=${token}; Path=/; HttpOnly; SameSite=Strict`);
        return json({ name: user.name });
      }

      case 'POST /login': {
        const email = (params.email || '').trim().toLowerCase();
        const password = params.password || '';

        if (pool) {
          try {
            const [rows] = await pool.execute('SELECT user_id, name, password_hash FROM users WHERE email = ?', [email]);
            if (rows.length === 0 || !verifyPassword(password, rows[0].password_hash)) {
              return error('Invalid email or password', 401);
            }
            const token = signSession(rows[0].user_id, rows[0].name);
            res.setHeader('Set-Cookie', `sid=${token}; Path=/; HttpOnly; SameSite=Strict`);
            return json({ name: rows[0].name });
          } catch (err) {
            console.error('MySQL login error:', err);
            return error('Database error during login', 500);
          }
        }

        const user = store.users.find(u => u.email === email);
        if (!user || !verifyPassword(password, user.password_hash)) {
          return error('Invalid email or password', 401);
        }

        const token = signSession(user.user_id, user.name);
        res.setHeader('Set-Cookie', `sid=${token}; Path=/; HttpOnly; SameSite=Strict`);
        return json({ name: user.name });
      }

      case 'POST /logout': {
        res.setHeader('Set-Cookie', 'sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict');
        return json({ ok: true });
      }

      case 'GET /me': {
        if (!requireAuth()) return;
        return json({ name: session.name });
      }

      case 'GET /categories': {
        if (!requireAuth()) return;
        const type = params.type;

        if (pool) {
          let sql = 'SELECT category_id, category_name, type, (user_id IS NOT NULL) AS custom FROM categories WHERE (user_id IS NULL OR user_id = ?) ';
          const args = [session.userId];
          if (type) {
            sql += 'AND type = ? ';
            args.push(type);
          }
          sql += 'ORDER BY category_name';
          const [rows] = await pool.execute(sql, args);
          return json(rows);
        }

        const list = store.categories
          .filter(c => (c.user_id === null || c.user_id === session.userId) && (!type || c.type === type))
          .map(c => ({
            category_id: c.category_id,
            category_name: c.category_name,
            type: c.type,
            custom: c.user_id !== null ? 1 : 0
          }));
        list.sort((a, b) => a.category_name.localeCompare(b.category_name));
        return json(list);
      }

      case 'POST /categories/add': {
        if (!requireAuth()) return;
        const name = (params.name || '').trim();
        const type = (params.type || '').trim().toUpperCase();
        if (!name) return error('Category name is required');
        if (name.length > 50) return error('Category name is too long (max 50)');
        if (type !== 'INCOME' && type !== 'EXPENSE') return error('Type must be INCOME or EXPENSE');

        if (pool) {
          const [clash] = await pool.execute('SELECT category_id FROM categories WHERE user_id IS NULL AND category_name = ? AND type = ?', [name, type]);
          if (clash.length > 0) return error('That category already exists');
          try {
            await pool.execute('INSERT INTO categories (user_id, category_name, type) VALUES (?, ?, ?)', [session.userId, name, type]);
            return json({ ok: true });
          } catch (err) {
            if (err.errno === 1062) return error('You already have that category');
            throw err;
          }
        }

        const clash = store.categories.find(c =>
          c.type === type &&
          c.category_name.toLowerCase() === name.toLowerCase() &&
          (c.user_id === null || c.user_id === session.userId)
        );
        if (clash) return error('That category already exists');

        store.categories.push({
          category_id: store.nextCatId++,
          user_id: session.userId,
          category_name: name,
          type
        });
        await persistBlob();
        return json({ ok: true });
      }

      case 'GET /transactions': {
        if (!requireAuth()) return;
        const month = parseInt(params.month, 10) || 0;
        const year = parseInt(params.year, 10) || 0;
        let from = params.from ? new Date(params.from) : null;
        let to = params.to ? new Date(params.to) : null;

        if (year > 0 && month > 0 && !from) {
          from = new Date(year, month - 1, 1);
          to = new Date(year, month, 1);
        } else if (year > 0 && !from) {
          from = new Date(year, 0, 1);
          to = new Date(year + 1, 0, 1);
        } else if (to) {
          to = new Date(to.getTime() + 86400000);
        }

        const type = params.type || null;
        const categoryId = parseInt(params.categoryId, 10) || 0;

        let items = store.transactions.filter(t => t.user_id === session.userId);
        if (from) items = items.filter(t => new Date(t.transaction_date) >= from);
        if (to) items = items.filter(t => new Date(t.transaction_date) < to);
        if (type) items = items.filter(t => t.type === type);
        if (categoryId > 0) items = items.filter(t => t.category_id === categoryId);

        items.sort((a, b) => (b.transaction_date.localeCompare(a.transaction_date) || b.transaction_id - a.transaction_id));

        const enriched = items.map(t => {
          const cat = store.categories.find(c => c.category_id === t.category_id);
          return {
            transaction_id: t.transaction_id,
            amount: t.amount,
            type: t.type,
            transaction_date: t.transaction_date,
            description: t.description,
            category_id: t.category_id,
            category_name: cat ? cat.category_name : 'Unknown'
          };
        });

        let income = 0, expense = 0;
        for (const t of enriched) {
          if (t.type === 'INCOME') income += Number(t.amount);
          else expense += Number(t.amount);
        }

        return json({ items: enriched, income, expense });
      }

      case 'POST /transactions/save': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const type = (params.type || '').toUpperCase();
        if (type !== 'INCOME' && type !== 'EXPENSE') return error('Type must be INCOME or EXPENSE');

        const categoryId = parseInt(params.categoryId, 10) || 0;
        const cat = store.categories.find(c => c.category_id === categoryId && (c.user_id === null || c.user_id === session.userId));
        if (!cat) return error('Invalid category');
        if (cat.type !== type) return error('Category does not match the transaction type');

        const amount = parseFloat(params.amount);
        if (isNaN(amount) || amount <= 0) return error('Amount must be greater than zero');
        if (amount > 9999999999.99) return error('Amount is too large');

        const date = params.date;
        if (!date) return error('Date is required');

        const description = (params.description || '').trim() || null;

        if (id === 0) {
          store.transactions.push({
            transaction_id: store.nextTxId++,
            user_id: session.userId,
            category_id: categoryId,
            amount: Number(amount.toFixed(2)),
            type,
            transaction_date: date,
            description
          });
        } else {
          const tx = store.transactions.find(t => t.transaction_id === id && t.user_id === session.userId);
          if (!tx) return error('Transaction not found');
          tx.category_id = categoryId;
          tx.amount = Number(amount.toFixed(2));
          tx.type = type;
          tx.transaction_date = date;
          tx.description = description;
        }

        await persistBlob();
        return json({ ok: true });
      }

      case 'POST /transactions/delete': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const index = store.transactions.findIndex(t => t.transaction_id === id && t.user_id === session.userId);
        if (index === -1) return error('Transaction not found');
        store.transactions.splice(index, 1);
        await persistBlob();
        return json({ ok: true });
      }

      case 'GET /dashboard': {
        if (!requireAuth()) return;
        const now = new Date();
        const month = parseInt(params.month, 10) || (now.getMonth() + 1);
        const year = parseInt(params.year, 10) || now.getFullYear();

        const start = new Date(year, month - 1, 1);
        const end = new Date(year, month, 1);
        const prevStart = new Date(year, month - 2, 1);

        let income = 0, expense = 0, prevIncome = 0, prevExpense = 0, allIncome = 0, allExpense = 0;
        const catMap = {};

        for (const t of store.transactions) {
          if (t.user_id !== session.userId) continue;
          const d = new Date(t.transaction_date);
          const amt = Number(t.amount);

          if (t.type === 'INCOME') allIncome += amt;
          else allExpense += amt;

          if (d >= start && d < end) {
            if (t.type === 'INCOME') income += amt;
            else {
              expense += amt;
              const cat = store.categories.find(c => c.category_id === t.category_id);
              const cName = cat ? cat.category_name : 'Unknown';
              if (!catMap[cName]) catMap[cName] = { total: 0, entries: 0 };
              catMap[cName].total += amt;
              catMap[cName].entries++;
            }
          } else if (d >= prevStart && d < start) {
            if (t.type === 'INCOME') prevIncome += amt;
            else prevExpense += amt;
          }
        }

        const categories = Object.entries(catMap).map(([category_name, data]) => ({
          category_name,
          total: data.total,
          entries: data.entries
        })).sort((a, b) => b.total - a.total);

        const trend = [];
        for (let i = 5; i >= 0; i--) {
          const d = new Date(year, month - 1 - i, 1);
          const next = new Date(year, month - i, 1);
          let mInc = 0, mExp = 0;
          for (const t of store.transactions) {
            if (t.user_id !== session.userId) continue;
            const td = new Date(t.transaction_date);
            if (td >= d && td < next) {
              if (t.type === 'INCOME') mInc += Number(t.amount);
              else mExp += Number(t.amount);
            }
          }
          trend.push({
            year: d.getFullYear(),
            month: d.getMonth() + 1,
            income: mInc,
            expense: mExp
          });
        }

        const budgets = store.budgets
          .filter(b => b.user_id === session.userId && b.budget_month === month && b.budget_year === year)
          .map(b => {
            const cat = store.categories.find(c => c.category_id === b.category_id);
            let spent = 0;
            for (const t of store.transactions) {
              if (t.user_id === session.userId && t.category_id === b.category_id && t.type === 'EXPENSE') {
                const td = new Date(t.transaction_date);
                if (td >= start && td < end) spent += Number(t.amount);
              }
            }
            const limit = Number(b.monthly_limit);
            const percent = limit > 0 ? Number(((spent * 100) / limit).toFixed(1)) : 0;
            const remaining = Number((limit - spent).toFixed(2));
            let status = 'OK';
            if (spent > limit) status = 'EXCEEDED';
            else if (spent >= 0.8 * limit) status = 'WARNING';
            return {
              budget_id: b.budget_id,
              category_id: b.category_id,
              category_name: cat ? cat.category_name : 'Unknown',
              monthly_limit: limit,
              spent,
              percent,
              remaining,
              status
            };
          });

        function calcChange(curr, prev) {
          if (!prev || prev === 0) return null;
          return Number((((curr - prev) * 100) / prev).toFixed(1));
        }

        return json({
          month,
          year,
          income,
          expense,
          balance: income - expense,
          totalSavings: allIncome - allExpense,
          prevIncome,
          prevExpense,
          incomeChange: calcChange(income, prevIncome),
          expenseChange: calcChange(expense, prevExpense),
          categories,
          budgets,
          trend
        });
      }

      case 'GET /budgets': {
        if (!requireAuth()) return;
        const now = new Date();
        const month = parseInt(params.month, 10) || (now.getMonth() + 1);
        const year = parseInt(params.year, 10) || now.getFullYear();

        const start = new Date(year, month - 1, 1);
        const end = new Date(year, month, 1);

        const list = store.budgets
          .filter(b => b.user_id === session.userId && b.budget_month === month && b.budget_year === year)
          .map(b => {
            const cat = store.categories.find(c => c.category_id === b.category_id);
            let spent = 0;
            for (const t of store.transactions) {
              if (t.user_id === session.userId && t.category_id === b.category_id && t.type === 'EXPENSE') {
                const td = new Date(t.transaction_date);
                if (td >= start && td < end) spent += Number(t.amount);
              }
            }
            const limit = Number(b.monthly_limit);
            const percent = limit > 0 ? Number(((spent * 100) / limit).toFixed(1)) : 0;
            const remaining = Number((limit - spent).toFixed(2));
            let status = 'OK';
            if (spent > limit) status = 'EXCEEDED';
            else if (spent >= 0.8 * limit) status = 'WARNING';
            return {
              budget_id: b.budget_id,
              category_id: b.category_id,
              category_name: cat ? cat.category_name : 'Unknown',
              monthly_limit: limit,
              spent,
              percent,
              remaining,
              status
            };
          });

        return json(list);
      }

      case 'POST /budgets/save': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const limit = parseFloat(params.limit);
        if (isNaN(limit) || limit <= 0) return error('Limit must be greater than zero');

        if (id > 0) {
          const b = store.budgets.find(x => x.budget_id === id && x.user_id === session.userId);
          if (!b) return error('Budget not found');
          b.monthly_limit = Number(limit.toFixed(2));
        } else {
          const categoryId = parseInt(params.categoryId, 10) || 0;
          const month = parseInt(params.month, 10);
          const year = parseInt(params.year, 10);
          const cat = store.categories.find(c => c.category_id === categoryId && c.type === 'EXPENSE' && (c.user_id === null || c.user_id === session.userId));
          if (!cat) return error('Please choose a valid expense category');

          const dup = store.budgets.find(b => b.user_id === session.userId && b.category_id === categoryId && b.budget_month === month && b.budget_year === year);
          if (dup) return error('A budget for this category and month already exists');

          store.budgets.push({
            budget_id: store.nextBudgetId++,
            user_id: session.userId,
            category_id: categoryId,
            monthly_limit: Number(limit.toFixed(2)),
            budget_month: month,
            budget_year: year
          });
        }
        await persistBlob();
        return json({ ok: true });
      }

      case 'POST /budgets/delete': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const index = store.budgets.findIndex(b => b.budget_id === id && b.user_id === session.userId);
        if (index === -1) return error('Budget not found');
        store.budgets.splice(index, 1);
        await persistBlob();
        return json({ ok: true });
      }

      case 'GET /goals': {
        if (!requireAuth()) return;
        const today = new Date(getTodayString());
        const list = store.goals
          .filter(g => g.user_id === session.userId)
          .map(g => {
            const target = Number(g.target_amount);
            const saved = Number(g.saved_amount);
            let percent = target > 0 ? Number(((saved * 100) / target).toFixed(1)) : 0;
            if (percent > 100) percent = 100;
            const remaining = Math.max(0, Number((target - saved).toFixed(2)));
            const deadlineDate = new Date(g.deadline);
            const daysLeft = Math.round((deadlineDate - today) / 86400000);
            return {
              goal_id: g.goal_id,
              goal_name: g.goal_name,
              target_amount: target,
              saved_amount: saved,
              deadline: g.deadline,
              percent,
              remaining,
              daysLeft
            };
          });
        list.sort((a, b) => a.deadline.localeCompare(b.deadline));
        return json(list);
      }

      case 'POST /goals/save': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const name = (params.name || '').trim();
        if (!name) return error('Goal name is required');
        const target = parseFloat(params.target);
        if (isNaN(target) || target <= 0) return error('Target amount must be greater than zero');
        const saved = parseFloat(params.saved || '0') || 0;
        const deadline = params.deadline;
        if (!deadline) return error('Deadline is required');

        if (id > 0) {
          const g = store.goals.find(x => x.goal_id === id && x.user_id === session.userId);
          if (!g) return error('Goal not found');
          g.goal_name = name;
          g.target_amount = Number(target.toFixed(2));
          g.saved_amount = Number(saved.toFixed(2));
          g.deadline = deadline;
        } else {
          store.goals.push({
            goal_id: store.nextGoalId++,
            user_id: session.userId,
            goal_name: name,
            target_amount: Number(target.toFixed(2)),
            saved_amount: Number(saved.toFixed(2)),
            deadline
          });
        }
        await persistBlob();
        return json({ ok: true });
      }

      case 'POST /goals/deposit': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const amount = parseFloat(params.amount);
        if (isNaN(amount) || amount <= 0) return error('Amount must be greater than zero');

        const g = store.goals.find(x => x.goal_id === id && x.user_id === session.userId);
        if (!g) return error('Goal not found');
        g.saved_amount = Number((g.saved_amount + amount).toFixed(2));
        await persistBlob();
        return json({ ok: true });
      }

      case 'POST /goals/delete': {
        if (!requireAuth()) return;
        const id = parseInt(params.id, 10) || 0;
        const index = store.goals.findIndex(g => g.goal_id === id && g.user_id === session.userId);
        if (index === -1) return error('Goal not found');
        store.goals.splice(index, 1);
        await persistBlob();
        return json({ ok: true });
      }

      case 'GET /report': {
        if (!requireAuth()) return;
        const month = parseInt(params.month, 10) || 0;
        const year = parseInt(params.year, 10) || new Date().getFullYear();

        const start = month === 0 ? new Date(year, 0, 1) : new Date(year, month - 1, 1);
        const end = month === 0 ? new Date(year + 1, 0, 1) : new Date(year, month, 1);

        let income = 0, expense = 0;
        const expCats = {}, incCats = {};

        for (const t of store.transactions) {
          if (t.user_id !== session.userId) continue;
          const d = new Date(t.transaction_date);
          if (d >= start && d < end) {
            const amt = Number(t.amount);
            const cat = store.categories.find(c => c.category_id === t.category_id);
            const cName = cat ? cat.category_name : 'Unknown';

            if (t.type === 'INCOME') {
              income += amt;
              if (!incCats[cName]) incCats[cName] = { total: 0, entries: 0 };
              incCats[cName].total += amt;
              incCats[cName].entries++;
            } else {
              expense += amt;
              if (!expCats[cName]) expCats[cName] = { total: 0, entries: 0 };
              expCats[cName].total += amt;
              expCats[cName].entries++;
            }
          }
        }

        const monthly = [];
        for (let m = 1; m <= 12; m++) {
          const mStart = new Date(year, m - 1, 1);
          const mEnd = new Date(year, m, 1);
          let mInc = 0, mExp = 0;
          for (const t of store.transactions) {
            if (t.user_id !== session.userId) continue;
            const td = new Date(t.transaction_date);
            if (td >= mStart && td < mEnd) {
              if (t.type === 'INCOME') mInc += Number(t.amount);
              else mExp += Number(t.amount);
            }
          }
          monthly.push({ month: m, income: mInc, expense: mExp });
        }

        const expenseByCategory = Object.entries(expCats).map(([category_name, data]) => ({
          category_name,
          total: data.total,
          entries: data.entries
        })).sort((a, b) => b.total - a.total);

        const incomeByCategory = Object.entries(incCats).map(([category_name, data]) => ({
          category_name,
          total: data.total,
          entries: data.entries
        })).sort((a, b) => b.total - a.total);

        return json({
          month,
          year,
          income,
          expense,
          net: income - expense,
          expenseByCategory,
          incomeByCategory,
          monthly
        });
      }

      default:
        return error('Unknown request', 404);
    }
  } catch (err) {
    console.error(err);
    return error('Something went wrong', 500);
  }
};
