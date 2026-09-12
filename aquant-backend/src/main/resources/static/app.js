/* 后端内置管理 SPA（vanilla JS） */
(function(){
  'use strict';
  var $ = function(s, r){ return (r||document).querySelector(s); };
  var $$ = function(s, r){ return Array.prototype.slice.call((r||document).querySelectorAll(s)); };
  var tokenKey = 'admin_token';
  var roleKey = 'admin_role';
  var token = localStorage.getItem(tokenKey) || '';
  var adminRole = localStorage.getItem(roleKey) || '';
  var route = location.hash ? location.hash.replace(/^#/, '') : '/';

  function api(path, opts){
    opts = opts || {};
    return fetch(path, {
      method: opts.method || 'GET',
      headers: Object.assign({'Content-Type':'application/json'}, token ? {'Authorization':'Bearer '+token} : {}),
      body: opts.body ? JSON.stringify(opts.body) : undefined
    }).then(function(r){ return r.json(); }).then(function(d){
      var ok = d && (d.success || d.code === 0 || d.code === 200);
      if(!ok){
        if(d && (d.code === 1000204 || d.code === 1000206)){ token=''; localStorage.removeItem(tokenKey); location.hash='#/login'; }
        throw new Error((d && d.message) || '请求失败');
      }
      return d.data;
    });
  }

  function esc(s){
    return String(s==null?'':s).replace(/[&<>"']/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; });
  }

  var toastTimer;
  function toast(msg){
    var t = $('#toast'); if(!t){ t=document.createElement('div'); t.id='toast'; document.body.appendChild(t); }
    t.className='msg show'; t.textContent=msg;
    clearTimeout(toastTimer); toastTimer=setTimeout(function(){ t.className='msg'; }, 2200);
  }

  function navigate(h){ if(location.hash !== '#'+h){ location.hash = '#'+h; } else { render(); } }

  function layout(active, inner){
    var nav = [
      ['/','管理主页'],['/watchlist','自选股票'],['/notifications','股票提醒'],['/articles','用户文章'],['/sync','数据同步'],['/settings','系统设置']
    ];
    if(adminRole === 'admin'){ nav.push(['/users','用户管理']); nav.push(['/strategies','量化策略']); }
    var links = nav.map(function(n){ return '<a href="#'+n[0]+'" class="'+(active===n[0]?'active':'')+'">'+n[1]+'</a>'; }).join('');
    var roleTag = adminRole === 'admin' ? '<span class="role-badge">管理员</span>' : '';
    return '<div class="topbar"><div class="brand">ApiBack 管理</div><nav class="nav">'+links+'</nav>'
      + '<span class="spacer"></span>'+roleTag+'<span class="logout" id="logout">退出登录</span></div>'
      + '<div class="page">'+inner+'</div>';
  }

  function loginPage(){
    $('#app').innerHTML =
      '<div class="login-wrap"><h1>ApiBack 管理登录</h1>'
      + '<div class="err" id="lerr"></div>'
      + '<div class="field"><label>用户名</label><input id="luser" autocomplete="username"></div>'
      + '<div class="field"><label>密码</label><input id="lpass" type="password" autocomplete="current-password"></div>'
      + '<button class="btn full" id="lbtn">登录</button></div>';
    var go = function(){
      var u = $('#luser').value.trim(), p = $('#lpass').value;
      if(!u||!p){ $('#lerr').textContent='请输入用户名和密码'; return; }
      $('#lbtn').disabled = true;
      api('/auth/login', {method:'POST', body:{username:u, password:p}}).then(function(d){
        token = d && d.token ? d.token : '';
        localStorage.setItem(tokenKey, token);
        adminRole = d && d.role ? d.role : '';
        localStorage.setItem(roleKey, adminRole);
        toast('登录成功');
        navigate('/');
      }).catch(function(e){ $('#lerr').textContent = e.message; $('#lbtn').disabled=false; });
    };
    $('#lbtn').addEventListener('click', go);
    $('#lpass').addEventListener('keydown', function(e){ if(e.key==='Enter') go(); });
  }

  function render(){
    if(route === '/login'){ loginPage(); bindLogout(false); return; }
    if(!token){ route = '/login'; location.hash = '#/login'; return; }
    var html = layout(route, '');
    $('#app').innerHTML = html;
    bindLogout(true);
    var page = $('#app .page');
    if(route === '/') page.innerHTML = home();
    else if(route === '/watchlist') watchlistPage(page);
    else if(route === '/notifications') notificationsPage(page);
    else if(route === '/articles') articlesPage(page);
    else if(route === '/sync') syncPage(page);
    else if(route === '/settings') settingsPage(page);
    else if(route === '/users') usersPage(page);
    else if(route === '/strategies') strategiesPage(page);
    else { location.hash = '#/'; }
  }

  function bindLogout(show){
    var el = $('#logout'); if(el) el.addEventListener('click', function(){ token=''; adminRole=''; localStorage.removeItem(tokenKey); localStorage.removeItem(roleKey); location.hash='#/login'; });
  }

  // ---------- 管理主页 ----------
  function home(){
    var cards = [
      {i:'⚙', t:'系统设置', d:'接口文档/调试日志/自动同步/预警扫描等开关', h:'#/settings'},
      {i:'⟳', t:'数据同步管理', d:'查看水位、手动同步、修改水位', h:'#/sync'},
      {i:'🔔', t:'股票提醒管理', d:'新增/编辑/删除/启停提醒', h:'#/notifications'},
      {i:'❤', t:'自选股票', d:'自选分组与成员管理', h:'#/watchlist'},
      {i:'📄', t:'用户文章管理', d:'我的文章：新建/编辑/删除/可见性', h:'#/articles'}
    ];
    if(adminRole === 'admin'){
      cards.push({i:'👥', t:'用户管理', d:'新增/编辑/启停/删除/角色', h:'#/users'});
      cards.push({i:'📈', t:'量化策略', d:'策略参数配置与回测快照重建', h:'#/strategies'});
    }
    var grid = cards.map(function(c){ return '<div class="mcard" data-href="'+c.h+'"><div class="ic">'+c.i+'</div><div class="tt">'+c.t+'</div><div class="ds">'+c.d+'</div></div>'; }).join('');
    return '<h2>管理主页</h2><p class="sub">仅展示可后台管理的模块；只读模块（文档/认证/策略/行情/指数/基金/分红/行业源等）已隐藏。</p>'
      + '<div class="card-grid">'+grid+'</div>'
      + '<div class="hint">提示：访问后可点击卡片进入对应管理页；下方主应用 API 仍可继续使用。</div>';
    // clicks added in render via delegation
  }

  // ---------- 系统设置 ----------
  function settingsPage(box){
    box.innerHTML = '<h2>系统设置</h2><p class="sub">运行时可开关后端能力</p><div class="card"></div>';
    api('/admin/config').then(function(list){
      var group1 = [
        {n:'docs.enabled', t:'接口文档（Swagger/Knife4j）', d:'控制 /doc.html 与接口文档访问'},
        {n:'logging.debug', t:'调试日志（DEBUG）', d:'开启后业务日志切到 DEBUG 级别'},
        {n:'druid.enabled', t:'Druid 监控', d:'控制 /druid 监控页访问'}
      ];
      var group2 = [
        {n:'sync.autoScheduled', t:'数据自动同步', d:'关闭后启动不再自动同步'},
        {n:'notification.enabled', t:'预警通知扫描', d:'关闭后停止股票/基金通知扫描'}
      ];
      var row = function(it){
        var cfg = (list||[]).find(function(c){ return c.name === it.n; });
        var on = cfg ? (cfg.value === '1') : false;
        return '<div class="setrow"><div><div class="st">'+it.t+'</div><div class="sd">'+it.d+'</div></div>'
          + '<label class="switch"><input type="checkbox" data-name="'+it.n+'" '+(on?'checked':'')+'><span class="slider"></span></label></div>';
      };
      box.innerHTML = '<h2>系统设置</h2><p class="sub">运行时可开关后端能力</p>'
        + '<div class="card"><h3>开发与调试</h3>'+group1.map(row).join('')+'</div><br>'
        + '<div class="card"><h3>后台任务</h3>'+group2.map(row).join('')+'</div>';
      $$('input[data-name]').forEach(function(inp){
        inp.addEventListener('change', function(){
          api('/admin/config', {method:'POST', body:{name: inp.dataset.name, value: inp.checked?'1':'0'}})
            .then(function(){ toast(inp.checked?'已开启':'已关闭'); })
            .catch(function(e){ toast(e.message); inp.checked = !inp.checked; });
        });
      });
    }).catch(function(e){ box.innerHTML = '<div class="empty">'+esc(e.message)+'</div>'; });
  }

  // ---------- 数据同步 ----------
  function syncPage(box){
    box.innerHTML = '<h2>数据同步管理</h2><p class="sub">查看同步水位 / 手动触发 / 修改水位</p>'
      + '<div class="toolbar"><button class="btn" id="trigSync">立即同步</button><button class="btn ghost" id="refSync">刷新</button></div>'
      + '<div class="card"><table><thead><tr><th>同步名称</th><th>水位值</th><th></th></tr></thead><tbody id="syncBody"></tbody></table></div>';
    var load = function(){
      api('/admin/sync/watermarks').then(function(list){
        var rows = (list||[]).map(function(it){
          return '<tr><td class="mono">'+esc(it.name)+'</td><td class="mono">'+esc(it.value)+'</td>'
            + '<td><button class="btn sm ghost" data-edit="'+esc(it.name)+'" data-val="'+esc(it.value)+'">修改</button></td></tr>';
        }).join('');
        $('#syncBody').innerHTML = rows || '<tr><td colspan="3" class="empty">暂无数据</td></tr>';
      }).catch(function(e){ $('#syncBody').innerHTML='<tr><td class="empty">'+esc(e.message)+'</td></tr>'; });
    };
    $('#trigSync').addEventListener('click', function(){
      api('/admin/sync/trigger', {method:'POST', body:{}}).then(function(d){ toast(d || '同步已开始'); }).catch(function(e){ toast(e.message); });
    });
    $('#refSync').addEventListener('click', load);
    $('#syncBody').addEventListener('click', function(e){
      var b = e.target.closest('[data-edit]'); if(!b) return;
      modal('修改同步水位', '<div class="field"><label>名称</label><input id="mname" value="'+esc(b.dataset.edit)+'" disabled></div>'
        + '<div class="field"><label>水位值（毫秒时间戳）</label><input id="mvalue" value="'+esc(b.dataset.val)+'"></div>',
        function(){ api('/admin/sync/watermark', {method:'POST', body:{name: b.dataset.edit, value: $('#mvalue').value.trim()}})
          .then(function(){ toast('已更新'); closeModal(); load(); }).catch(function(e){ toast(e.message); }); });
    });
    load();
  }

  // ---------- 股票提醒 ----------
  function notificationsPage(box){
    box.innerHTML = '<h2>股票提醒管理</h2><p class="sub">新增/编辑/删除/启停提醒</p>'
      + '<div class="toolbar"><button class="btn" id="nAdd">新增提醒</button><button class="btn ghost" id="nRef">刷新</button></div>'
      + '<div class="card"><table><thead><tr><th>代码</th><th>资产</th><th>类型</th><th>阈值</th><th>状态</th><th>最后通知</th><th>操作</th></tr></thead><tbody id="nBody"></tbody></table></div>';
    var typeMap = {1:'价格/净值',2:'双均线',3:'网格',4:'MACD'};
    var load = function(){
      api('/stock/notification/listAll').then(function(list){
        var rows = (list||[]).map(function(n){
          return '<tr><td>'+esc(n.stockCode)+'</td><td>'+esc(n.assetType)+'</td><td>'+(typeMap[n.type]||n.type)+'</td>'
            + '<td>'+(n.thresholdValue==null?'-':n.thresholdValue)+'</td>'
            + '<td><span class="tag '+(n.isEnabled===1?'on':'off')+'">'+(n.isEnabled===1?'启用':'停用')+'</span></td>'
            + '<td>'+(n.lastNotifyAt?'':'')+esc(n.lastNotifyAt)+'</td>'
            + '<td><button class="btn sm ghost" data-act="edit" data-id="'+n.id+'">编辑</button> '
            + '<button class="btn sm ghost" data-act="toggle" data-id="'+n.id+'">'+(n.isEnabled===1?'停用':'启用')+'</button> '
            + '<button class="btn sm danger" data-act="del" data-id="'+n.id+'">删除</button></td></tr>';
        }).join('');
        $('#nBody').innerHTML = rows || '<tr><td colspan="7" class="empty">暂无提醒</td></tr>';
      }).catch(function(e){ $('#nBody').innerHTML='<tr><td class="empty">'+esc(e.message)+'</td></tr>'; });
    };
    $('#nRef').addEventListener('click', load);
    $('#nAdd').addEventListener('click', function(){ notificationForm(null); });
    $('#nBody').addEventListener('click', function(e){
      var b = e.target.closest('[data-act]'); if(!b) return;
      var id = Number(b.dataset.id);
      if(b.dataset.act==='toggle'){ toggleNotify(id); }
      else if(b.dataset.act==='del'){ if(confirm('确认删除该提醒？')) delNotify(id); }
      else if(b.dataset.act==='edit'){ loadNotifyForEdit(id); }
    });
    function toggleNotify(id){
      api('/stock/notification/listAll').then(function(list){
        var n = (list||[]).find(function(x){ return x.id===id; });
        if(!n) return;
        return api('/stock/notification/save', {method:'POST', body:{id:n.id, stockCode:n.stockCode, assetType:n.assetType, type:n.type, thresholdValue:n.thresholdValue, params:n.params, isEnabled: n.isEnabled===1?0:1}});
      }).then(function(){ toast('已更新'); load(); }).catch(function(e){ toast(e.message); });
    }
    function delNotify(id){
      api('/stock/notification/delete', {method:'POST', body:{id:id}}).then(function(){ toast('已删除'); load(); }).catch(function(e){ toast(e.message); });
    }
    function loadNotifyForEdit(id){
      api('/stock/notification/listAll').then(function(list){
        var n = (list||[]).find(function(x){ return x.id===id; });
        if(n) notificationForm(n);
      });
    }
    function notificationForm(n){
      var isEdit = !!n;
      modal(isEdit?'编辑提醒':'新增提醒',
        '<div class="field"><label>标的代码</label><input id="ncode" value="'+(n?esc(n.stockCode):'')+'" placeholder="如 600519 / 000001"></div>'
        + '<div class="field"><label>资产类型</label><select id="nasset"><option value="STOCK"'+(n&&n.assetType!=='FUND'?' selected':'')+'>股票</option><option value="FUND"'+(n&&n.assetType==='FUND'?' selected':'')+'>基金</option></select></div>'
        + '<div class="field"><label>通知类型</label><select id="ntype">'+[1,2,3,4].map(function(t){ return '<option value="'+t+'"'+(n&&n.type===t?' selected':'')+'>'+(typeMap[t]||t)+'</option>'; }).join('')+'</select></div>'
        + '<div class="field"><label>触发条件（价格类型：UP=达到/高于，DOWN=跌破）</label><select id="ncond"><option value="UP"'+(n&&String(n.params||'').indexOf('DOWN')>=0?'':' selected')+'>UP</option><option value="DOWN"'+(n&&String(n.params||'').indexOf('DOWN')>=0?' selected':'')+'>DOWN</option></select></div>'
        + '<div class="field"><label>阈值</label><input id="nthr" type="number" value="'+(n&&n.thresholdValue!=null?n.thresholdValue:'')+'"></div>',
        function(){
          var payload = { id: isEdit?n.id:undefined, stockCode:$('#ncode').value.trim(), assetType:$('#nasset').value, type:Number($('#ntype').value) };
          if(payload.type===1){ payload.thresholdValue = Number($('#nthr').value)||undefined; payload.params = JSON.stringify({condition:$('#ncond').value}); }
          if(!payload.stockCode){ toast('请填写标的代码'); throw new Error('no'); }
          api('/stock/notification/save', {method:'POST', body:payload}).then(function(){ toast('已保存'); closeModal(); load(); }).catch(function(e){ toast(e.message); });
        });
    }
    load();
  }

  // ---------- 用户文章 ----------
  function articlesPage(box){
    box.innerHTML = '<h2>用户文章管理</h2><p class="sub">我的文章：新建/编辑/删除/切换可见性</p>'
      + '<div class="toolbar"><button class="btn" id="aAdd">新建文章</button><button class="btn ghost" id="aRef">刷新</button></div>'
      + '<div class="card"><table><thead><tr><th>标题</th><th>可见性</th><th>创建时间</th><th>操作</th></tr></thead><tbody id="aBody"></tbody></table></div>';
    var load = function(){
      api('/article/my/list').then(function(list){
        var rows = (list||[]).map(function(a){
          return '<tr><td>'+esc(a.title)+'</td><td>'+(a.visibility===0?'公开':'私密')+'</td><td>'+esc(a.createdAt)+'</td>'
            + '<td><button class="btn sm ghost" data-act="edit" data-id="'+a.id+'">编辑</button> '
            + '<button class="btn sm ghost" data-act="vis" data-id="'+a.id+'">'+(a.visibility===0?'设为私密':'设为公开')+'</button> '
            + '<button class="btn sm danger" data-act="del" data-id="'+a.id+'">删除</button></td></tr>';
        }).join('');
        $('#aBody').innerHTML = rows || '<tr><td colspan="4" class="empty">暂无文章</td></tr>';
      }).catch(function(e){ $('#aBody').innerHTML='<tr><td class="empty">'+esc(e.message)+'</td></tr>'; });
    };
    $('#aRef').addEventListener('click', load);
    $('#aAdd').addEventListener('click', function(){ articleForm(null); });
    $('#aBody').addEventListener('click', function(e){
      var b = e.target.closest('[data-act]'); if(!b) return;
      var id = Number(b.dataset.id);
      if(b.dataset.act==='del'){ if(confirm('确认删除该文章？')) api('/article/delete',{method:'POST',body:{id:id}}).then(function(){toast('已删除');load();}).catch(function(e){toast(e.message);}); }
      else if(b.dataset.act==='vis'){ visArticle(id); }
      else if(b.dataset.act==='edit'){ loadArticleForEdit(id); }
    });
    function visArticle(id){
      api('/article/my/list').then(function(list){
        var a=(list||[]).find(function(x){return x.id===id;}); if(!a) return;
        return api('/article/update-visibility',{method:'POST',body:{id:id, visibility: a.visibility===0?1:0}});
      }).then(function(){ toast('已更新'); load(); }).catch(function(e){ toast(e.message); });
    }
    function loadArticleForEdit(id){
      fetch('/article/detail?id='+id, {headers:{'Authorization':'Bearer '+token}}).then(function(r){return r.json();}).then(function(d){
        if(d && d.data) articleForm({id:d.data.id, title:d.data.title, content:d.data.content, visibility:d.data.visibility});
      });
    }
    function articleForm(a){
      var isEdit = !!a;
      modal(isEdit?'编辑文章':'新建文章',
        '<div class="field"><label>标题</label><input id="atitle" value="'+(a?esc(a.title):'')+'"></div>'
        + '<div class="field"><label>内容</label><textarea id="acontent" rows="6" style="width:100%;border:1px solid var(--line);border-radius:8px;padding:8px;">'+(a?esc(a.content):'')+'</textarea></div>'
        + '<div class="field"><label>可见性</label><select id="avis"><option value="0"'+(!a||a.visibility===0?' selected':'')+'>公开</option><option value="1"'+(a&&a.visibility===1?' selected':'')+'>私密</option></select></div>',
        function(){
          var title=$('#atitle').value.trim(), content=$('#acontent').value.trim();
          if(!title||!content){ toast('标题和内容不能为空'); throw new Error('no'); }
          var body = isEdit ? {id:a.id, title:title, content:content, visibility:Number($('#avis').value)}
                            : {title:title, content:content, visibility:Number($('#avis').value)};
          api('/article/'+(isEdit?'update':'create'), {method:'POST', body:body}).then(function(){ toast('已保存'); closeModal(); load(); }).catch(function(e){ toast(e.message); });
        });
    }
    load();
  }

  // ---------- 自选 ----------
  function watchlistPage(box){
    box.innerHTML = '<h2>自选股票</h2><p class="sub">自选分组与成员管理</p>'
      + '<div class="toolbar"><button class="btn" id="gAdd">新增分组</button><button class="btn ghost" id="gRef">刷新</button></div>'
      + '<div id="gWrap"></div>';
    var load = function(){
      api('/stockWatchlist/group/list').then(function(groups){
        var html = (groups||[]).map(function(g){
          var rows = (g.stocks||[]).map(function(s){
            return '<tr><td>'+esc(s.stockCode)+'</td><td>'+esc(s.stockName)+'</td>'
              + '<td><button class="btn sm danger" data-gid="'+g.id+'" data-code="'+esc(s.stockCode)+'">移除</button></td></tr>';
          }).join('');
          return '<div class="card" style="margin-bottom:14px"><h3>'+esc(g.name)+' <span style="color:var(--muted);font-weight:400">('+((g.stocks||[]).length)+')</span></h3>'
            + '<div class="toolbar"><button class="btn sm ghost" data-gn="'+esc(g.name)+'" data-gid="'+g.id+'">改名</button>'
            + '<button class="btn sm danger" data-gdel="'+g.id+'">删除分组</button>'
            + '<span style="flex:1"></span>'
            + '<button class="btn sm" data-add="'+g.id+'">添加股票</button></div>'
            + '<table><thead><tr><th>代码</th><th>名称</th><th></th></tr></thead><tbody>'+ (rows||'<tr><td colspan="3" class="empty">暂无</td></tr>') +'</tbody></table></div>';
        }).join('');
        $('#gWrap').innerHTML = html || '<div class="empty">暂无自选分组</div>';
      }).catch(function(e){ $('#gWrap').innerHTML='<div class="empty">'+esc(e.message)+'</div>'; });
    };
    $('#gRef').addEventListener('click', load);
    $('#gAdd').addEventListener('click', function(){
      modal('新增分组','<div class="field"><label>分组名称</label><input id="gname"></div>', function(){
        var name=$('#gname').value.trim(); if(!name){ toast('请输入名称'); throw new Error('no'); }
        api('/stockWatchlist/group/create',{method:'POST',body:{name:name}}).then(function(){ toast('已创建'); closeModal(); load(); }).catch(function(e){ toast(e.message); });
      });
    });
    $('#gWrap').addEventListener('click', function(e){
      var el = e.target.closest('[data-gdel],[data-gn],[data-add]'); if(!el) return;
      if(el.hasAttribute('data-gdel')){
        if(confirm('确认删除该分组？')) api('/stockWatchlist/group/delete',{method:'POST',body:{id:Number(el.dataset.gdel)}}).then(function(){toast('已删除');load();}).catch(function(e){toast(e.message);});
      } else if(el.hasAttribute('data-gn')){
        modal('重命名分组','<div class="field"><label>分组名称</label><input id="gname" value="'+esc(el.dataset.gn)+'"></div>', function(){
          var name=$('#gname').value.trim(); if(!name){ toast('请输入名称'); throw new Error('no'); }
          api('/stockWatchlist/group/update',{method:'POST',body:{id:Number(el.dataset.gid),name:name}}).then(function(){toast('已更新');closeModal();load();}).catch(function(e){toast(e.message);});
        });
      } else if(el.hasAttribute('data-add')){
        modal('添加股票','<div class="field"><label>股票代码</label><input id="scode" placeholder="如 600519"></div>', function(){
          var code=$('#scode').value.trim(); if(!code){ toast('请输入代码'); throw new Error('no'); }
          api('/stockWatchlist/stock/add',{method:'POST',body:{groupId:Number(el.dataset.add),stockCode:code}}).then(function(){toast('已添加');closeModal();load();}).catch(function(e){toast(e.message);});
        });
      } else if(e.target.closest('[data-code]')){
        var rm = e.target.closest('[data-code]');
        if(confirm('确认移除该股票？')) api('/stockWatchlist/stock/remove',{method:'POST',body:{groupId:Number(rm.dataset.gid),stockCode:rm.dataset.code}}).then(function(){toast('已移除');load();}).catch(function(e){toast(e.message);});
      }
    });
    load();
  }

  // ---------- 用户管理 ----------
  function usersPage(box){
    box.innerHTML = '<h2>用户管理</h2><p class="sub">超级管理员：新增/编辑/启停/删除/角色/重置密码</p>'
      + '<div class="toolbar"><button class="btn" id="uAdd">新增用户</button><button class="btn ghost" id="uRef">刷新</button></div>'
      + '<div class="card"><table><thead><tr><th>ID</th><th>登录名</th><th>昵称</th><th>邮箱</th><th>状态</th><th>角色</th><th>创建时间</th><th>操作</th></tr></thead><tbody id="uBody"></tbody></table></div>';
    var load = function(){
      api('/admin/user/list').then(function(list){
        var rows = (list||[]).map(function(u){
          return '<tr><td>'+u.id+'</td><td>'+esc(u.username)+'</td><td>'+esc(u.nickname||'')+'</td><td>'+esc(u.email||'')+'</td>'
            + '<td><span class="tag '+(u.status===1?'on':'off')+'">'+(u.status===1?'启用':'禁用')+'</span></td>'
            + '<td>'+(u.role==='admin'?'管理员':'普通用户')+'</td><td>'+esc(u.createdAt||'')+'</td>'
            + '<td><button class="btn sm ghost" data-act="edit" data-id="'+u.id+'">编辑</button> '
            + '<button class="btn sm ghost" data-act="reset" data-id="'+u.id+'">重置密码</button> '
            + '<button class="btn sm ghost" data-act="toggle" data-id="'+u.id+'" data-status="'+u.status+'">'+(u.status===1?'禁用':'启用')+'</button> '
            + '<button class="btn sm danger" data-act="del" data-id="'+u.id+'">删除</button></td></tr>';
        }).join('');
        $('#uBody').innerHTML = rows || '<tr><td colspan="8" class="empty">暂无用户</td></tr>';
      }).catch(function(e){ $('#uBody').innerHTML='<tr><td class="empty" colspan="8">'+esc(e.message)+'</td></tr>'; });
    };
    $('#uRef').addEventListener('click', load);
    $('#uAdd').addEventListener('click', function(){ userForm(null); });
    $('#uBody').addEventListener('click', function(e){
      var b = e.target.closest('[data-act]'); if(!b) return;
      var id = Number(b.dataset.id);
      if(b.dataset.act==='del'){ if(confirm('确认删除该用户？')) api('/admin/user/delete',{method:'POST',body:{id:id}}).then(function(){toast('已删除');load();}).catch(function(e){toast(e.message);}); }
      else if(b.dataset.act==='toggle'){ api('/admin/user/status',{method:'POST',body:{id:id,status: b.dataset.status==='1'?0:1}}).then(function(){toast('已更新');load();}).catch(function(e){toast(e.message);}); }
      else if(b.dataset.act==='reset'){ resetForm(id); }
      else if(b.dataset.act==='edit'){ loadUserForEdit(id); }
    });
    function userForm(u){
      var isEdit = !!u;
      modal(isEdit?'编辑用户':'新增用户',
        (isEdit?'':'<div class="field"><label>登录名</label><input id="uuser"></div>')
        + '<div class="field"><label>昵称</label><input id="unick" value="'+((u&&u.nickname)?esc(u.nickname):'')+'"></div>'
        + '<div class="field"><label>邮箱</label><input id="uemail" value="'+((u&&u.email)?esc(u.email):'')+'"></div>'
        + (isEdit?'<div class="field"><label>角色</label><select id="urole"><option value="user"'+(u.role!=='admin'?' selected':'')+'>普通用户</option><option value="admin"'+(u.role==='admin'?' selected':'')+'>管理员</option></select></div>':'')
        + (!isEdit?'<div class="field"><label>初始密码</label><input id="upwd" type="password"></div>':''),
        function(){
          if(!isEdit){
            var un=$('#uuser').value.trim(), ps=$('#upwd').value;
            if(!un||!ps){ toast('登录名和初始密码不能为空'); throw new Error('no'); }
            api('/admin/user/create',{method:'POST',body:{username:un,password:ps,nickname:$('#unick').value.trim(),email:$('#uemail').value.trim()}}).then(function(){toast('已创建');closeModal();load();}).catch(function(e){toast(e.message);});
          } else {
            api('/admin/user/update',{method:'POST',body:{id:u.id,nickname:$('#unick').value.trim(),email:$('#uemail').value.trim(),role:$('#urole').value}}).then(function(){toast('已保存');closeModal();load();}).catch(function(e){toast(e.message);});
          }
        });
    }
    function loadUserForEdit(id){
      api('/admin/user/list').then(function(list){
        var u=(list||[]).find(function(x){return x.id===id;}); if(u) userForm(u);
      });
    }
    function resetForm(id){
      modal('重置密码','<div class="field"><label>新密码</label><input id="rppwd" type="password"></div>',function(){
        var p=$('#rppwd').value; if(!p){ toast('请输入新密码'); throw new Error('no'); }
        api('/admin/user/reset-password',{method:'POST',body:{id:id,password:p}}).then(function(){toast('已重置');closeModal();}).catch(function(e){toast(e.message);});
      });
    }
    load();
  }

  // ---------- 量化策略 ----------
  var stratCfg = null;
  function strategiesPage(box){
    box.innerHTML = '<h2>量化策略管理</h2><p class="sub">参数可配置；保存后需「重新生成快照」才生效</p>'
      + '<div class="toolbar"><button class="btn" id="sSave">保存配置</button><button class="btn ghost" id="sRef">刷新</button><button class="btn danger" id="sRefresh">重新生成快照</button></div>'
      + '<div id="stratWrap"></div>';
    var fields = function(id, val, ph){ return '<input id="'+id+'" value="'+esc((val||[]).join(','))+'" placeholder="'+ph+'" style="width:100%;max-width:320px;border:1px solid var(--line);border-radius:6px;padding:6px 8px;">'; };
    var comboLabel = function(combo, cols){ return cols.filter(function(c){ return combo[c]!=null && combo[c]!==''; }).map(function(c){ return combo[c]; }).join('/'); };
    var pct = function(v){ if(v==null||isNaN(v)) return '-'; return (Number(v)*100).toFixed(2)+'%'; };
    var statsHtml = function(s, title, cols){
      if(!s || !s.batchNo){ return ''; }
      var rel = s.reliability||{};
      var relKeys = ['高','中','低','样本不足','未知'].filter(function(k){ return rel[k]!=null; });
      var relBar = relKeys.map(function(k){ return '<span class="tag '+(k==='高'?'on':k==='中'?'':'off')+'">'+esc(k)+' '+rel[k]+'</span>'; }).join(' ');
      var comboRows = (s.topCombos||[]).map(function(co){
        return '<tr><td>'+esc(comboLabel(co, cols))+'</td><td>'+co.count+'</td><td>'+pct(co.avgReturn)+'</td><td>'+pct(co.avgWin)+'</td></tr>';
      }).join('');
      return '<div class="card" style="margin-top:10px"><h3>📊 '+esc(title)+' 快照统计</h3>'
        + '<p class="sd">批次 '+esc(s.batchNo)+'（'+new Date(Number(s.batchNo)).toLocaleString()+'） · 共 '+s.total+' 行</p>'
        + '<p>'+relBar+'</p>'
        + (comboRows ? '<table><thead><tr><th>参数组合</th><th>命中股票数</th><th>平均收益</th><th>平均胜率</th></tr></thead><tbody>'+comboRows+'</tbody></table>' : '<p class="sd">暂无组合统计</p>')
        + '</div>';
    };
    var render = function(){
      var c = stratCfg || {};
      var dual = c.dualMa||{}, mom = c.momentum||{}, mac = c.macd||{}, gri = c.grid||{};
      var wm = (c.$watermarks)||{};
      var stats = (c.$stats)||{};
      var wmHtml = function(k){ var v=wm[k]; return v?new Date(Number(v)).toLocaleString():'—'; };
      var html = ''
        + '<div class="card"><h3>双均线（maShort / maLong / 年数）</h3><p class="sd">快照水位：'+wmHtml('dualMa')+'</p>'
        + '<div class="field"><label>短期均线</label>'+fields('dm_short', dual.maShort, '如 5,10,20')+'</div>'
        + '<div class="field"><label>长期均线</label>'+fields('dm_long', dual.maLong, '如 30,60,120')+'</div>'
        + '<div class="field"><label>回测年数</label>'+fields('dm_years', dual.years, '如 1,2,3,5')+'</div></div><br>'
        + '<div class="card"><h3>动量（lookback / 年数）</h3><p class="sd">快照水位：'+wmHtml('momentum')+'</p>'
        + '<div class="field"><label>回看天数</label>'+fields('mm_lb', mom.lookback, '如 10,20,60')+'</div>'
        + '<div class="field"><label>回测年数</label>'+fields('mm_years', mom.years, '如 1,2,3,5')+'</div></div><br>'
        + '<div class="card"><h3>MACD（fast / slow / signal / 年数）</h3><p class="sd">快照水位：'+wmHtml('macd')+'</p>'
        + '<div class="field"><label>快线</label><input id="mc_f" type="number" value="'+(mac.fast!=null?mac.fast:12)+'"></div>'
        + '<div class="field"><label>慢线</label><input id="mc_s" type="number" value="'+(mac.slow!=null?mac.slow:26)+'"></div>'
        + '<div class="field"><label>信号</label><input id="mc_g" type="number" value="'+(mac.signal!=null?mac.signal:9)+'"></div>'
        + '<div class="field"><label>回测年数</label>'+fields('mc_years', mac.years, '如 1,2,3,5')+'</div></div><br>'
        + '<div class="card"><h3>网格（比例% / 层数 / 年数）</h3><p class="sd">快照水位：'+wmHtml('grid')+'</p>'
        + '<div class="field"><label>网格比例(%)</label><input id="gr_r" type="number" value="'+(gri.rate!=null?gri.rate:3)+'"></div>'
        + '<div class="field"><label>网格层数</label><input id="gr_c" type="number" value="'+(gri.count!=null?gri.count:5)+'"></div>'
        + '<div class="field"><label>回测年数</label>'+fields('gr_years', gri.years, '如 1,2,3,5')+'</div></div>'
        + statsHtml(stats.dualMa, '双均线', ['maShort','maLong','years'])
        + statsHtml(stats.momentum, '动量', ['lookback','years'])
        + statsHtml(stats.macd, 'MACD', ['fast','slow','signal','years'])
        + statsHtml(stats.grid, '网格', ['rate','count','years']);
      $('#stratWrap').innerHTML = html;
    };
    var load = function(){
      api('/admin/strategy/config').then(function(d){
        var c = d.config || {}; c.$watermarks = d.watermarks || {}; c.$stats = d.stats || {};
        stratCfg = c; render();
      }).catch(function(e){ $('#stratWrap').innerHTML='<div class="empty">'+esc(e.message)+'</div>'; });
    };
    var splitNums = function(id){ return ($('#'+id).value||'').split(',').map(function(s){ s=s.trim(); return s===''?null:Number(s); }).filter(function(n){ return n!=null && !isNaN(n); }); };
    $('#sRef').addEventListener('click', load);
    $('#sSave').addEventListener('click', function(){
      if(!stratCfg) return;
      var body = {
        dualMa:  { maShort: splitNums('dm_short'),  maLong: splitNums('dm_long'),  years: splitNums('dm_years') },
        momentum:{ lookback: splitNums('mm_lb'),    years: splitNums('mm_years') },
        macd:    { fast: Number($('#mc_f').value),  slow: Number($('#mc_s').value), signal: Number($('#mc_g').value), years: splitNums('mc_years') },
        grid:    { rate: Number($('#gr_r').value),  count: Number($('#gr_c').value), years: splitNums('gr_years') }
      };
      api('/admin/strategy/config', {method:'POST', body: body}).then(function(){ toast('配置已保存，可重新生成快照'); load(); }).catch(function(e){ toast(e.message); });
    });
    $('#sRefresh').addEventListener('click', function(){
      if(confirm('将按当前配置重新生成全部回测快照（可能较慢），确认继续？')){
        api('/admin/strategy/refresh', {method:'POST', body:{}}).then(function(){ toast('已触发快照重建，请稍后刷新观察水位变化'); }).catch(function(e){ toast(e.message); });
      }
    });
    load();
  }

  // ---------- 弹窗 ----------
  function modal(title, bodyHtml, onSubmit){
    var wrap = document.createElement('div');
    wrap.className = 'modal-backdrop';
    wrap.innerHTML = '<div class="modal"><h3>'+esc(title)+'</h3><div class="mbody">'+bodyHtml+'</div>'
      + '<div class="formactions"><button class="btn ghost" id="mCancel">取消</button><button class="btn" id="mOk">确定</button></div></div>';
    document.body.appendChild(wrap);
    wrap.addEventListener('click', function(e){ if(e.target === wrap) closeModal(); });
    $('#mCancel', wrap).addEventListener('click', closeModal);
    $('#mOk', wrap).addEventListener('click', function(){
      try { onSubmit(); } catch(err){ if(err.message==='no'){} else { } }
    });
  }
  function closeModal(){ var m = $('.modal-backdrop'); if(m) m.remove(); }

  window.addEventListener('hashchange', function(){ route = location.hash.replace(/^#/, '') || '/'; render(); });
  // home card clicks handled by delegation on document
  document.addEventListener('click', function(e){
    var c = e.target.closest('.mcard[data-href]'); if(c){ location.hash = c.dataset.href; }
  });

  render();
})();