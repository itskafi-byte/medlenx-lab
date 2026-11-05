#!/usr/bin/env python3
"""Static guard for the shared tinted icon and its navigation/KPI mappings."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab/ui'
icon = (root/'components/FrostedIcon.kt').read_text()
nav = (root/'shell/MlxBottomNav.kt').read_text()
dest = (root/'navigation/Destination.kt').read_text()
kpi = (root/'screens/analytics/AnalyticsScreen.kt').read_text()
card = (root/'components/Cards.kt').read_text().split('fun KpiCard(',1)[1].split('/** Mini KPI',1)[0]
for part in ('icon: ImageVector', 'baseColor: Color', 'iconSize: Dp = 24.dp', 'containerSize: Dp = 40.dp',
             'shape: Shape = RoundedCornerShape(12.dp)', '.size(containerSize).clip(shape)',
             'baseColor.copy(alpha = 0.15f)', 'contentAlignment = Alignment.Center', 'tint = baseColor'):
    assert part in icon, part
for name, color, image in [('Scan','0xFF0EA5E9','DocumentScanner'),('Analytics','0xFF8B5CF6','Analytics'),
                            ('Hub','0xFF10B981','Hub'),('Team','0xFFF59E0B','Groups')]:
    assert f'Destination.{name} -> Color({color})' in nav
    assert f'"{name}", Icons.Rounded.{image}' in dest
assert 'if (active)' in nav and 'FrostedIcon(icon = dest.icon, baseColor = color)' in nav
assert 'Icon(dest.icon, contentDescription = null, tint = Mlx.Text500' in nav
assert 'selectable(selected = active, role = Role.Tab' in nav and '.selectableGroup()' in nav
for label, image, color in [('Total Captured','ReceiptLong','MaterialTheme.colorScheme.primary'),
                            ('Identified Medicines','Medication','Color(0xFF8B5CF6)'),
                            ('Target Share','PieChart','Color(0xFFF59E0B)')]:
    assert f'"{label}" -> Icons.Rounded.{image}' in kpi
    assert f'"{label}" -> {color}' in kpi
assert '"Active Doctor Coverage" -> Icons.Rounded.AssignmentInd' in kpi
assert '"Active Doctor Coverage" -> Color(0xFF10B981)' in kpi
assert 'FrostedIcon(icon = icon, baseColor = iconColor)' in card
print('frostediconcheck: component, selected-state navigation and KPI mappings PASS')
