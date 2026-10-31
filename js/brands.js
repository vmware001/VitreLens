/* ═══════════════════════════════════════════════════════════
   brands.js — 品牌选择元数据（不包含任何品牌图形）
   公开仓库不内置自绘或官方品牌图标；选择项只用于文字预设与自定义内容。
   ═══════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  var BRANDS = [
    { id: 'leica', label: '徕卡', accent: '#E8402F' },
    { id: 'hasselblad', label: '哈苏', accent: '#E3A45C' },
    { id: 'zeiss', label: '蔡司', accent: '#3D7BE0' },
    { id: 'ricoh', label: '理光', accent: '#E23B3B' },
    { id: 'sony', label: '索尼', accent: '#FFFFFF' },
    { id: 'canon', label: '佳能', accent: '#E2373F' },
    { id: 'nikon', label: '尼康', accent: '#FFD400' },
    { id: 'fujifilm', label: '富士', accent: '#E60012' },
    { id: 'apple', label: 'Apple', accent: '#FFFFFF' },
    { id: 'none', label: '无', accent: '#FFFFFF' }
  ];

  global.LG = global.LG || {};
  global.LG.Logos = {
    list: BRANDS,
    // 没有用户提供的位图时，不生成图形，也不占用版式宽度。
    measure: function () { return 0; },
    draw: function () { return 0; },
    accent: function (id) {
      for (var i = 0; i < BRANDS.length; i++) {
        if (BRANDS[i].id === id) return BRANDS[i].accent;
      }
      return '#FFFFFF';
    }
  };
})(window);
