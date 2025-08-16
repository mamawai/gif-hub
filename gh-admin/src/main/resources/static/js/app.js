// API 基础URL
const API_BASE_URL = '/audit';

// 当前页码和页面大小
let currentPage = 1;
const pageSize = 10;

// 当前查看的GIF ID
let currentGifId = null;

// 当前查看的列表类型：pending, passed, rejected
let currentListType = 'pending';

// DOM 元素
const elements = {
    gifList: document.getElementById('gif-list'),
    pagination: document.getElementById('pagination'),
    pageTitle: document.getElementById('page-title'),
    pendingLink: document.getElementById('pending-link'),
    passedLink: document.getElementById('passed-link'),
    rejectedLink: document.getElementById('rejected-link'),
    gifPreview: document.getElementById('gif-preview'),
    gifTitle: document.getElementById('gif-title'),
    gifDescription: document.getElementById('gif-description'),
    rejectReasonContainer: document.getElementById('reject-reason-container'),
    rejectReason: document.getElementById('reject-reason'),
    btnReject: document.getElementById('btn-reject'),
    btnApprove: document.getElementById('btn-approve'),
    btnConfirmReject: document.getElementById('btn-confirm-reject')
};

// 模态框实例
let auditModal = null;

// 页面加载完成后执行
document.addEventListener('DOMContentLoaded', function() {
    // 初始化模态框
    auditModal = new bootstrap.Modal(document.getElementById('auditModal'));
    
    // 加载待审核列表
    loadPendingGifs();
    
    // 绑定事件
    elements.pendingLink.addEventListener('click', () => switchListType('pending'));
    elements.passedLink.addEventListener('click', () => switchListType('passed'));
    elements.rejectedLink.addEventListener('click', () => switchListType('rejected'));
    
    elements.btnReject.addEventListener('click', showRejectForm);
    elements.btnApprove.addEventListener('click', approveGif);
    elements.btnConfirmReject.addEventListener('click', rejectGif);

    // 添加批量删除按钮事件
    const deleteAllBtn = document.getElementById('delete-all-btn');
    if (deleteAllBtn) {
        deleteAllBtn.addEventListener('click', deleteBatch);
    }
});

/**
 * 切换列表类型
 * @param {string} type - 列表类型：pending, passed, rejected
 */
function switchListType(type) {
    currentListType = type;
    currentPage = 1;
    
    // 更新导航栏状态
    elements.pendingLink.classList.remove('active');
    elements.passedLink.classList.remove('active');
    elements.rejectedLink.classList.remove('active');
    
    switch(type) {
        case 'pending':
            elements.pendingLink.classList.add('active');
            elements.pageTitle.textContent = '待审核GIF列表';
            loadPendingGifs();
            break;
        case 'passed':
            elements.passedLink.classList.add('active');
            elements.pageTitle.textContent = '已通过GIF列表';
            // 当前控制器接口没有提供已审核的GIF列表，这里可以适当提示用户
            showNoDataMessage('已通过的GIF列表功能暂未实现');
            break;
        case 'rejected':
            elements.rejectedLink.classList.add('active');
            elements.pageTitle.textContent = '已拒绝GIF列表';
            // 当前控制器接口没有提供已拒绝的GIF列表，这里可以适当提示用户
            showNoDataMessage('已拒绝的GIF列表功能暂未实现');
            break;
    }
}

/**
 * 显示无数据消息
 * @param {string} message - 提示信息
 */
function showNoDataMessage(message) {
    elements.gifList.innerHTML = '';
    const tr = document.createElement('tr');
    tr.innerHTML = `<td colspan="5" class="text-center">${message}</td>`;
    elements.gifList.appendChild(tr);
    elements.pagination.innerHTML = '';
}

/**
 * 加载待审核GIF列表
 */
function loadPendingGifs() {
    const url = `${API_BASE_URL}/pending?page=${currentPage}&pageSize=${pageSize}`;
    
    fetch(url)
        .then(response => response.json())
        .then(data => {
            if (data.status === 200) {
                renderGifList(data.data);
                // TODO: 处理分页
            } else {
                console.error('加载GIF列表失败:', data.message);
                showNoDataMessage('加载数据失败');
            }
        })
        .catch(error => {
            console.error('请求出错:', error);
            showNoDataMessage('网络错误，请稍后重试');
        });
}

/**
 * 渲染GIF列表
 * @param {Array} gifs - GIF列表数据
 */
function renderGifList(gifs) {
    elements.gifList.innerHTML = '';
    
    if (!gifs || gifs.length === 0) {
        const tr = document.createElement('tr');
        tr.innerHTML = '<td colspan="5" class="text-center">暂无数据</td>';
        elements.gifList.appendChild(tr);
        return;
    }
    
    gifs.forEach(gif => {
        const tr = document.createElement('tr');
        
        tr.innerHTML = `
            <td>${gif.id}</td>
            <td><img src="${gif.fileUrl}" alt="GIF预览" class="gif-preview-thumbnail"></td>
            <td>${gif.title || '无标题'}</td>
            <td>${gif.description || '无描述'}</td>
            <td>
                <button class="btn btn-primary btn-sm view-btn" data-id="${gif.id}">查看</button>
            </td>
        `;
        
        elements.gifList.appendChild(tr);
        
        // 绑定查看按钮事件
        const viewBtn = tr.querySelector('.view-btn');
        viewBtn.addEventListener('click', () => openAuditModal(gif));
    });
}

/**
 * 打开审核模态框
 * @param {Object} gif - GIF对象
 */
function openAuditModal(gif) {
    currentGifId = gif.id;
    
    // 重置表单
    elements.rejectReasonContainer.style.display = 'none';
    elements.btnReject.classList.remove('d-none');
    elements.btnApprove.classList.remove('d-none');
    elements.btnConfirmReject.classList.add('d-none');
    elements.rejectReason.value = '';
    
    // 显示GIF详情
    elements.gifPreview.src = gif.fileUrl;
    elements.gifTitle.textContent = gif.title || '无标题';
    elements.gifDescription.textContent = gif.description || '无描述';
    
    // 显示模态框
    auditModal.show();
}

/**
 * 显示拒绝表单
 */
function showRejectForm() {
    elements.rejectReasonContainer.style.display = 'block';
    elements.btnReject.classList.add('d-none');
    elements.btnApprove.classList.add('d-none');
    elements.btnConfirmReject.classList.remove('d-none');
}

/**
 * 通过审核
 */
function approveGif() {
    if (!currentGifId) return;
    
    const url = `${API_BASE_URL}/process/${currentGifId}`;
    const params = new URLSearchParams();
    params.append('status', 1); // 1表示通过
    
    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: params
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 200 && data.data) {
            alert('审核已通过');
            auditModal.hide();
            loadPendingGifs();
        } else {
            alert('操作失败: ' + (data.message || '未知错误'));
        }
    })
    .catch(error => console.error('请求出错:', error));
}

/**
 * 拒绝审核
 */
function rejectGif() {
    if (!currentGifId) return;
    
    const rejectReason = elements.rejectReason.value.trim();
    if (!rejectReason) {
        alert('请填写拒绝原因');
        return;
    }
    
    const url = `${API_BASE_URL}/process/${currentGifId}`;
    const params = new URLSearchParams();
    params.append('status', 0); // 0表示拒绝
    
    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: params
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 200 && data.data) {
            alert('已拒绝审核');
            auditModal.hide();
            loadPendingGifs();
        } else {
            alert('操作失败: ' + (data.message || '未知错误'));
        }
    })
    .catch(error => console.error('请求出错:', error));
}

/**
 * 批量删除已审核记录和下架的GIF
 */
function deleteBatch() {
    if (!confirm('确定要删除所有已审核记录和下架的GIF吗？此操作不可恢复！')) {
        return;
    }
    
    fetch(`${API_BASE_URL}/deleteBatch`, {
        method: 'DELETE'
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 200 && data.data) {
            alert('删除成功');
            // 刷新当前列表
            if (currentListType === 'pending') {
                loadPendingGifs();
            }
        } else {
            alert('删除失败: ' + (data.message || '未知错误'));
        }
    })
    .catch(error => {
        console.error('请求出错:', error);
        alert('网络错误，请稍后重试');
    });
} 